package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    // 提前将lua脚本加载为RedisScript,不然每次都加在会有很多io操作消耗资源
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        // 类加载时将lua脚本加载进来
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 业务线程的代理对象,使得SECKILL_ORDER_EXECUTOR中的线程可以获取并调用createVoucherOrder,保证事务不失效
    private IVoucherOrderService proxy;

    // 阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 创建一个单线程的线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 通过PostConstruct注解,类一加载就执行初始化,将异步更新数据库的任务提交至线程池中
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 将从阻塞队列取任务并更新数据库的操作
     * 抽象封装为类VoucherOrderHandler
     * 并提交至线程池异步执行
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取阻塞队列中的订单信息,没有就阻塞在此处,不会占用CPU资源
                    VoucherOrder order = orderTasks.take();
                    // 2. 创建订单
                    addRedissonLockBeforeCreateVoucherOrder(order);
                } catch (Exception e) {
                    log.error("处理订单异常: {}", e);
                }
            }
        }
    }

    private void addRedissonLockBeforeCreateVoucherOrder(VoucherOrder order) {
        // 此处已经是线程池中的线程了,无法通过UserHolder获取UserId,注意!!!
        // 1. 获取userId
        Long userId = order.getUserId();

        // 2. 创建锁对象,并获取锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean gotLock = lock.tryLock();

        // 出现概率较小,redis层已判断用户是否购买过该商品
        if (!gotLock) {
            log.error("不允许重复下单");
            return;
        }

        // 3. 调用创建VoucherOrder的函数,在try-finally块中保证锁释放
        try {
            proxy.createVoucherOrderWithOrderObject(order);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 基于原CreateVoucherOrder改造
     * 不需要再创建VoucherOrder对象了(已经创建好了)
     * 直接根据传入的VoucherOrder对象更新数据库记录即可
     * @param order
     */
    @Transactional
    public void createVoucherOrderWithOrderObject(VoucherOrder order) {
        // 5. 一人一单
        Long userId = order.getUserId();
        // 5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", order.getVoucherId()).count();
        // 5.2 判断是否已存在
        // 出现概率较小,redis层已判断
        if (count > 0) {
            log.error("Redis层错误导致重复查询数据库,用户已经购买过该商品");
            return;
        }

        // 6. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", order.getVoucherId()).gt("stock", 0).update();

        // 出现概率较小,redis层已判断
        if (!success) {
            log.error("数据库更新出错,扣减商品库存失败");
            return;
        }

        // 7 订单写入数据库
        save(order);
    }

    /**
     * 抢购秒杀优化券
     * 基于lua脚本和redis缓存快速判断用户购买资格
     * 异步执行数据库的更新操作，优化响应速度
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1. 执行lua脚本,得到结果
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());

        // 2. 如果返回不为0, 说明没有购买资格,返回errorMsg
        if (result.intValue() != 0) {
            if (result.intValue() == 1) {
                // 2.1 结果为1说明库存不足
                return Result.fail("库存不足");
            } else {
                // 2.2 结果为2说明重复下单
                return Result.fail("同一用户不可重复下单");
            }
        }
        // 3. 有资格则将相关信息保存至阻塞队列中,异步更新数据库
        // 3.1 获取订单相关信息 - 生成订单id
        long orderId = redisIdWorker.nextId("order");
        // 3.2创建订单记录
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);

        // 3.3 添加至阻塞队列,异步更新数据库记录
        orderTasks.add(voucherOrder);

        // 4. 获得代理对象,防止子线程调用createVoucherOrder时事务失效
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 4. 返回订单id,便于后续完成支付的逻辑
        return Result.ok(orderId);
    }


// 这部分逻辑交给LUA执行,缓存库存和已下单用户id至Redis,优化秒杀的判断速度,同时配合异步完成完整的秒杀优化
    /**
     * 抢购秒杀优化券
     * 涉及两张表的操作，需要加事务注解
     * 乐观锁解决超卖
     * @param voucherId
     * @return
     */
    public Result seckillVoucher1(Long voucherId) {
        // 1. 查询优化券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀活动尚未开始");
        }

        // 3. 判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 已经结束
            return Result.fail("秒杀活动已经结束");
        }

        // 4. 判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        // 尝试获取分布式锁,获取成功就继续创建订单,否则就返回errorMsg,不进行重试,因为此处的并发逻辑是同一用户的并发请求,是非法的
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId); // [基于redisson]
        // 尝试获取锁
        boolean gotLock = lock.tryLock(); // 默认不重试, 超时时间30秒 [基于redisson]
//        boolean gotLock = lock.tryLock(1200); // 测试的时候时间先暂时设置的久一点
        if (!gotLock) {
            // 获取锁失败直接返回,不重试,同一用户的并发请求是非法的
            return Result.fail("不允许重复下单");
        }

        // try-finally 块保证分布式锁一定释放
        try {
            // 调用创建订单函数
            // 获取代理对象，确保事务被正确开启
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 创建秒杀优惠券订单
     * 锁的范围针对用户，每个锁是锁同一用户，同一用户的并发请求，第一个到达的请求获取到基于该用户id的分布式锁
     * @param voucherId
     * @return
     */
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5. 一人一单
        Long userId = UserHolder.getUser().getId();
        // 5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2 判断是否已存在
        if (count > 0) {
            // 不可重复购买
            return Result.fail("不可重复购买秒杀活动优惠券");
        }

        // 6. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();

        if (!success) {
            return Result.fail("库存不足");
        }

        // 7. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1 创建订单id
        long orderId = redisIdWorker.nextId("order");
        // 7.2 给秒杀优化券的订单设置主键uuid、用户id、代金券id
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 7.3 订单写入数据库
        save(voucherOrder);

        return Result.ok(orderId);
    }
}
