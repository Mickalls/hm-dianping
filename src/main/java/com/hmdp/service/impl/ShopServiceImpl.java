package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 基于cache aside策略的更新商户详情信息
     * 开启@Transactional注解,保证Redis与数据库的一致性,出现错误就回滚
     * @param shop
     * @return
     */
    @Transactional // 保证
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }

    /**
     * 根据id查询商铺信息(Redis缓存)
     * @param id
     * @return
     */
    public Result queryById(Long id) {
        // 方案1 - 防止缓存穿透的商户详情查询 (适用于当用户老是查询不存在的商户时)
        // Shop shop = queryWithPassThrough(id); // 旧调用方式(调用本地的实现)

        // Shop shop = cacheClient // 调用工具包的方式
        //        .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 方案2 - 基于互斥锁：防止缓存击穿的商户详情查询 (针对于热点商户)
        // Shop shop = queryWithMutex(id);

        // 方案3 - 基于逻辑过期：防止缓存击穿的商户详情查询 (针对于热点商户)
        // Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient // 调用工具包的方式
                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, LOCK_SHOP_KEY, Shop.class, this::getById, 10L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 既可以提前预热、也可以缓存重建
     * 将热key(流量大的店铺)的value(店铺详情信息)存储到Redis中
     * @param id
     * @param expireSeconds
     */
    /*
    public void saveHotShopToRedis(Long id, Long expireSeconds) {
        // 1. 查询店铺数据
        Shop shop = getById(id);
        // 2. 封装成逻辑过期数据
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 3. 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    */

    /**
     * 线程池的个数取决于热点key个数，这个要具体问题具体分析
     */
    /*
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    */


    /**
     * 基于逻辑过期的防止缓存击穿策略
     * @param id
     * @return
     */
    /*
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 查询 Redis 缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 缓存未命中直接返回空
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        // 3. 若命中,判断是否逻辑过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject shopJSONObject = (JSONObject) redisData.getData(); // 转为的是JSONObject
        Shop shop = JSONUtil.toBean(shopJSONObject, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 4. 未过期直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }

        // 5. 已过期,进行缓存重建
        // 5.1 尝试获取互斥锁,注意只尝试一次
        String lockKey = LOCK_SHOP_KEY + id;
        boolean gotLock = tryLock(lockKey);
        if (gotLock) {
            // 5.2 获取成功,开启独立线程查数据库后返回逻辑过期的热点key数据
            // 实现缓存重建
            // 这里采用线程池去做
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 应该设置hotkey的逻辑过期时间为30分钟
                    this.saveHotShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }

        // 5.3 获取失败,直接返回逻辑过期的热点key数据
        return shop;
    }
    */

    /**
     * 基于互斥锁解决缓存击穿的商户详情查询
     * 适用情况: 被请求的资源是热点key
     * 还没有提取为工具包
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从Redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 缓存命中，直接返回解析对象
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 3. 如果是空值缓存命中，直接返回null防止缓存穿透
        if (shopJson != null) {
            return null; // 注意，之前插入的是空字符串""
        }

        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        boolean gotLock = false;

        try {
            // 4. 循环尝试获取互斥锁
            while (!gotLock) {
                gotLock = tryLock(lockKey);
                if (!gotLock) {
                    // 4.1 获取锁失败则稍后重试
                    Thread.sleep(THREAD_SLEEP_SECS);
                }
            }

            // 5. 再次尝试从缓存获取数据，防止重复查询数据库（双重检查锁）
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // 6. 查询数据库
            shop = getById(id);
            if (shop == null) {
                // 7. 数据不存在，写入空值到缓存防止缓存穿透，有效期短
                stringRedisTemplate.opsForValue().set(key, EMPTY_STRING, CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 8. 数据存在，写入Redis缓存并设置有效期
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        } finally {
            // 9. 释放互斥锁
            if (gotLock) {
                unLock(lockKey);
            }
        }

        // 10. 返回数据库查询结果
        return shop;
    }

    /**
     * 封装基于缓存空对象的防止缓存穿透的商户详情查询方法
     * 适用情况: 被请求的资源总是不存在
     */
    /*
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 查询 Redis 缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 缓存命中直接返回,这个判断的函数即使shopJson == null|""|"\n"|"\t" 时都返回false
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // added. 判断命中的是否是空值(如果是空值,则返回,防止缓存穿透)
        if (shopJson != null) {
            // 注意,当时插入的是""
            return null;
        }

        // 3. 未命中就查询数据库
        Shop shop = getById(id);

        // 4. 数据库不存在返回404
        if (shop == null) {
            // 将空值写入redis防止缓存穿透,有效期设置短一点(2分钟)
            // 不能插入 null 值,会抛出 IllegalArgumentException 异常
            stringRedisTemplate.opsForValue().set(key, EMPTY_STRING, CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }

        // 5. 数据库存在就先写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 6. 返回
        return shop;
    }
     */

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
