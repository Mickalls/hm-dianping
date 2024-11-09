package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 负责缓存重建的线程池
     * 线程池的线程个数取决于热点key个数，这个要具体问题具体分析
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 查询 Redis 缓存
        String jsonStr = stringRedisTemplate.opsForValue().get(key);

        // 2. 缓存命中直接返回,这个判断的函数即使shopJson == null|""|"\n"|"\t" 时都返回false
        if (StrUtil.isNotBlank(jsonStr)) {
            return JSONUtil.toBean(jsonStr, type);
        }

        // added. 判断命中的是否是空值(如果是空值,则返回,防止缓存穿透)
        if (jsonStr != null) {
            // 注意,当时插入的是""
            return null;
        }

        // 3. 未命中就查询数据库
        R r = dbFallback.apply(id);

        // 4. 数据库不存在返回404
        if (r == null) {
            // 将空值写入redis防止缓存穿透,有效期设置短一点(2分钟)
            // 不能插入 null 值,会抛出 IllegalArgumentException 异常
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }

        // 5. 数据库存在就先写入Redis
        this.set(key, r, time, unit);

        // 6. 返回
        return r;
    }




    /**
     * 基于逻辑过期的防止缓存击穿策略
     * @param id
     * @return
     */
    public <R, ID> R queryWithLogicalExpire(String dataKeyPrefix, ID id, String mutexKeyPrefix, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = dataKeyPrefix + id;
        // 1. 查询 Redis 缓存
        String jsonStr = stringRedisTemplate.opsForValue().get(key);

        // 2. 缓存未命中直接返回空
        if (StrUtil.isBlank(jsonStr)) {
            return null;
        }

        // 3. 若命中,判断是否逻辑过期
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        JSONObject shopJSONObject = (JSONObject) redisData.getData(); // 转为的是JSONObject
        R r = JSONUtil.toBean(shopJSONObject, type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 4. 未过期直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 5. 已过期,进行缓存重建
        // 5.1 尝试获取互斥锁,注意只尝试一次
        String lockKey = mutexKeyPrefix + id;
        boolean gotLock = tryLock(lockKey);
        if (gotLock) {
            // 5.2 获取成功,开启独立线程查数据库后返回逻辑过期的热点key数据
            // 实现缓存重建
            // 这里采用线程池去做
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 先查数据库
                    R r1 = dbFallback.apply(id);
                    // 再重建缓存
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }

        // 5.3 获取失败,直接返回逻辑过期的热点key数据
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
