package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 抢购秒杀优化券
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 创建秒杀优惠券订单
     * 锁的范围针对用户，每个锁是锁同一用户，同一用户的并发请求，第一个到达的请求获取到基于该用户id的分布式锁
     * @param voucherId
     * @return
     */
    public Result createVoucherOrder(Long voucherId);
}
