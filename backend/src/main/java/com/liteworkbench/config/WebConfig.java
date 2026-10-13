package com.liteworkbench.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web 层配置：本地调试跨域放行 + 前端单页应用深链兜底 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 本地前端调试源跨域放行 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /** 深链兜底：不含点的前端路由回落到 index.html；/api 段显式排除，接口不存在时必须给 404 信封而不是页面 */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/{path:(?!api$)[^\\.]*}").setViewName("forward:/index.html");
        registry.addViewController("/{first:(?!api$)[^\\.]*}/**/{path:[^\\.]*}").setViewName("forward:/index.html");
    }
}
