package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询商铺信息(Redis缓存)
     * @param id
     * @return
     */
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 查询 Redis 缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 缓存命中直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        // 3. 未命中就查询数据库
        Shop shop = getById(id);

        // 4. 数据库不存在返回404
        if (shop == null) {
            // 将空值写入redis防止缓存穿透,有效期设置短一点(2分钟)
//            stringRedisTemplate.opsForValue().set(key, null, CACHE_NULL_TTL, TimeUnit.MINUTES);

            return Result.fail("店铺不存在");
        }

        // 5. 数据库存在就先写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 6. 返回
        return Result.ok(shop);
    }

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
}
