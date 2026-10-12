package com.liteworkbench.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Sa-Token 拦截器装配：登录校验 + 登录上下文注入 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /** 免登录路径：公钥下发、登录接口与容器探活，其余 actuator 端点同样需要登录 */
    private static final String[] WHITE_LIST = {
            "/api/v1/auth/public-key",
            "/api/v1/auth/login",
            "/actuator/health",
            "/actuator/health/**",
    };

    /** 注册登录校验与上下文拦截器 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/**")
                .excludePathPatterns(WHITE_LIST);
        registry.addInterceptor(new LoginContextInterceptor())
                .addPathPatterns("/api/**");
    }
}
