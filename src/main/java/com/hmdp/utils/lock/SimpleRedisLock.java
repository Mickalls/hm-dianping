package com.hmdp.utils.lock;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;

    // key = keyPrefix + name, 完整的分布式锁的key值
    private String lockKey;
    // 分布式锁的名称,与具体业务需求有关
    private String name;
    // 分布式锁前缀
    private static final String keyPrefix = "lock:";

    // 每个线程的唯一标识
    private String threadUUID;
    // 每个线程的唯一标识的前缀
    private static final String idPrefix = UUID.randomUUID().toString() + "-";

    // 提前将lua脚本加载为RedisScript,不然每次都加在会有很多io操作消耗资源
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        // 类加载时将lua脚本加载进来
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String _name, StringRedisTemplate _stringRedisTemplate) {
        // 设置成员变量
        this.stringRedisTemplate = _stringRedisTemplate;
        name = _name;

        // 初始化当前分布式锁的key
        this.lockKey = keyPrefix + name;

        // 初始化当前线程的唯一标识
        this.threadUUID = idPrefix + Thread.currentThread().getId();
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, threadUUID, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用 lua 脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), threadUUID);
    }
}
