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

    /**
     * 根据商品类型分页查询店铺信息
     * @param typeId 店铺类型
     * @param current 分页查询的页码
     * @param x 店家x坐标 经度
     * @param y 店家y坐标 纬度
     * @return
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
