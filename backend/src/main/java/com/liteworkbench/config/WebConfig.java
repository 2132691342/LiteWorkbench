package com.liteworkbench.config;

import cn.hutool.core.map.MapUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.ParameterizableViewController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Web 层配置：本地调试跨域放行、接口兜底与前端单页应用深链兜底 */
@Slf4j
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

    /** 接口兜底：/api/** 未命中业务接口时回 404 信封，排在业务接口之后、深链兜底之前 */
    @Bean
    public SimpleUrlHandlerMapping apiFallbackHandlerMapping() {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(1);
        mapping.setUrlMap(MapUtil.of("/api/**", (HttpRequestHandler) this::writeNotFound));
        return mapping;
    }

    /** 深链兜底：不含点的前端路由回落到 index.html；带点的静态资源与 /api 都不会落到这里 */
    @Bean
    public SimpleUrlHandlerMapping spaFallbackHandlerMapping() {
        ParameterizableViewController forward = new ParameterizableViewController();
        forward.setViewName("forward:/index.html");
        Map<String, Object> urlMap = new LinkedHashMap<>();
        urlMap.put("/{path:[^\\.]*}", forward);
        urlMap.put("/**/{path:[^\\.]*}", forward);
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(2);
        mapping.setUrlMap(urlMap);
        return mapping;
    }

    private void writeNotFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ErrorCodeEnum code = ErrorCodeEnum.RESOURCE_NOT_FOUND;
        log.warn("[api.notfound] uri={}", request.getRequestURI());
        response.setStatus(code.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(R.fail(code)));
    }
}
