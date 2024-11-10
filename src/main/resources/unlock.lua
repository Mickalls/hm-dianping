-- KEYS[1] 是锁的key  ARGV[1] 是当前线程的唯一标识(基于uuid和线程id生成)
-- 比较线程标识与锁中的标识是否一致
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 释放锁
    return redis.call('del', KEYS[1])
end
return 0