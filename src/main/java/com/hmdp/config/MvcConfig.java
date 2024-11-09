package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Token刷新拦截器先执行,登陆拦截器后执行,通过order设置优先级,或者根据先后添加顺序先后执行(order默认为0)
        // BUG!!! order越小,越优先执行
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns( // 过滤路径,这些路径不进行拦截
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/voucher/**"
                ).order(1);
    }
}
