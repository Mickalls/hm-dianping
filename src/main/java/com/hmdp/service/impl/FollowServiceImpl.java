package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 执行关注/取关操作
     * @param followUserId 要被当前用户关注的用户id
     * @param isFollow true - 关注   false - 取关
     * @return
     */
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW_KEY + userId;
        // 1. 判断是关注还是取关
        if (isFollow) {
            // 2. 新增关注数据
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 把这个关注数据也加入到redis中
                // sadd key(follow:userId) followUserId : 表示userId关注了followUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 3. 删除关注数据 delete from tb_follow where userId = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("follow_user_id", followUserId)
                    .eq("user_id", userId));
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 判断当前用户是否关注了某个用户
     * @param followUserId "某个用户"的ID
     * @return
     */
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        // 1. 查询是否关注 select count from tb_follow where userId = ? and follow_user_id = ?
        Integer count = query().eq("follow_user_id", followUserId).eq("user_id", userId).count();

        // 2. 判断是否关注 count > 0 说明关注 则true
        return Result.ok(count > 0);
    }

    /**
     * 查询共同关注
     * @param followUserId
     * @return
     */
    public Result followCommons(Long followUserId) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 利用redis set求交集
        String userKey = FOLLOW_KEY + userId;
        String followUserKey = FOLLOW_KEY + followUserId;

        // 得到所有共同关注的用户的id
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(userKey, followUserKey);

        // 解析为 id 列表
        List<Long> commonIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        // 查询 id 列表对应的用户信息
        List<UserDTO> userDTOS = userService.listByIds(commonIds)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        if (userDTOS == null || userDTOS.isEmpty()) {
            return Result.ok();
        }
        return Result.ok(userDTOS);
    }
}
