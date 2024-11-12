package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 执行关注/取关操作
     * @param followUserId 要被当前用户关注的用户id
     * @param isFollow true - 关注   false - 取关
     * @return http response body 中的 data
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断当前用户是否关注了某个用户
     * @param followUserId "某个用户"的ID
     * @return
     */
    Result isFollow(Long followUserId);

    /**
     * 查询共同关注
     * @param followUserId
     * @return
     */
    Result followCommons(Long followUserId);
}
