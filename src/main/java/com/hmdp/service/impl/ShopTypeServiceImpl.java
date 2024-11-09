package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 基于Redis缓存的店铺类型查询
     * @return
     */
    public Result queryList() {
        String key = RedisConstants.CACHE_SHOPTYPE_KEY;
        // 1. 查询Redis
        List<String> cacheShopType = stringRedisTemplate.opsForList().range(key, 0, -1);

        // 2. 缓存命中直接返回
        if (cacheShopType != null && !cacheShopType.isEmpty()) {
            List<ShopType> shopTypeList = cacheShopType.stream()
                    .map(jsonStr -> JSONUtil.toBean(jsonStr, ShopType.class))
                    .collect(Collectors.toList());

            return Result.ok(shopTypeList);
        }

        // 3. 缓存未命中,查数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        // 4. 数据库中不存在返回错误
        if (shopTypeList == null || shopTypeList.isEmpty()) {
            return Result.fail("店铺类型不存在");
        }

        // 5. 存在则先写回缓存，并设置30分钟的过期时间
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypeList.stream()
                .map(JSONUtil::toJsonStr).collect(Collectors.toList()));
        stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 6. 返回
        return Result.ok(shopTypeList);
    }

}
/*
    @GetMapping("list")
    public Result queryTypeList() {
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        return Result.ok(typeList);
    }
*/