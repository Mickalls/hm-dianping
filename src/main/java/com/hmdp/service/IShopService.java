package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询商铺信息(Redis缓存)
     * @param id
     * @return
     */
    Result queryById(Long id);

    /**
     * 基于cache aside策略的更新商户详情信息
     * @param shop
     * @return
     */
    Result update(Shop shop);
}
