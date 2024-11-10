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
import com.hmdp.utils.lock.SimpleRedisLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 抢购秒杀优化券
     * 涉及两张表的操作，需要加事务注解
     * 乐观锁解决超卖
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
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
