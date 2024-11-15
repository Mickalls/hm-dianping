package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 登陆功能
     * @param loginForm
     * @param session
     * @return
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 退出登陆功能
     * @param token 用户登陆时生成并保存到Redis中的校验码
     * @return
     */
    Result logout(String token);

    /**
     * 签到功能
     * @return
     */
    Result sign();

    /**
     * 统计本月连续签到天数
     * @return
     */
    Result signCount();
}
