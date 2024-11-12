package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        boolean isPhoneInvalid = RegexUtils.isPhoneInvalid(phone);

        // 2. 如果不符合，返回错误信息
        if (isPhoneInvalid) {
            return Result.fail("手机号格式错误");
        }

        // 3. 生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4. 保存验证码到Redis，设置验证码两分钟有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. 发送验证码
        log.info("模拟短信发送,验证码为: {}", code);

        // 6. 返回ok
        return Result.ok();
    }

    /**
     * 登陆功能
     * @param loginForm
     * @param session
     * @return
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号和验证码，不一致就报错
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 手机号格式错误,返回错误
            return Result.fail("手机号格式错误");
        }
        // 从 Redis 中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }
        // 2. 根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        // 3. 判断用户是否存在
        if (user == null) {
            // 4. 不存在创建新用户,用该手机号+随机昵称创建一个新用户
            user = createUserWithPhone(phone);
        }

        // 5. 保存用户信息到 Redis
        // 5.1 生成token作为登陆令牌
        String token = UUID.randomUUID().toString(true);

        // 5.2 将user对象转为Hash存储
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);

        // 5.2.5 将user转换为Map
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        // 5.3 存储到Redis中
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 5.4 设置过期时间30分钟
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 6. 返回ok
        return Result.ok(token);
    }

    /**
     * 退出登录功能
     * @param token 用户登陆时生成并保存到Redis中的校验码
     * @return
     */
    public Result logout(String token) {
        // 1. 获取用户id
        UserDTO userDTO = UserHolder.getUser();

        // 2. 拼接出tokenKey 并删除Redis中响应记录
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.delete(tokenKey);

        // 3. 删除 UserHolder 中的用户记录
        UserHolder.removeUser();

        return Result.ok();
    }

    /**
     * 利用手机号创建新用户,昵称随机生成,其他字段不管
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        // 1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2. 保存用户
        save(user);
        return user;
    }
}
