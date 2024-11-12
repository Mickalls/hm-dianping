-- 1.1 订单id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]
-- 1.3 订单id
local orderId = ARGV[3]

-- 2. 构造key
-- 2.1 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单key (存储所有购买该商品的用户id List)
local orderKey = 'seckill:order:' .. voucherId

-- 3. 业务逻辑
-- 3.1 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) < 0) then
    -- 库存不足,没资格购买,返回1
    return 1
end

-- 3.2 判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 用户已购买该商品,没资格购买,返回2
    return 2
end

-- 3.3 扣减库存
redis.call('incrby', stockKey, -1)

-- 3.4 创建订单
redis.call('sadd', orderKey, userId)

-- 4. 发送消息到消息队列
-- xadd stream.orders * k1 v1 k2 v2
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0