package com.hmdp.utils.lock;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;

    private String key; // key = keyPrefix + name
    private String name;
    private static final String keyPrefix = "lock:";

    public SimpleRedisLock(String _name, StringRedisTemplate _stringRedisTemplate) {
        this.stringRedisTemplate = _stringRedisTemplate;
        name = _name;
        this.key = keyPrefix + name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程表示
        long threadId = Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId + "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 释放锁
        stringRedisTemplate.delete(key);
    }
}
