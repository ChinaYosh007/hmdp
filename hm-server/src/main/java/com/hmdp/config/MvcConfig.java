package com.hmdp.config;

import com.hmdp.inteceptor.LoginInteceptor;
import com.hmdp.inteceptor.RefreshTokenInteceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer
{
    @Resource
    private RefreshTokenInteceptor refreshTokenInteceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInteceptor())
                .order(1)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login");
        registry.addInterceptor(refreshTokenInteceptor).order(0).addPathPatterns("/**");
    }

}
