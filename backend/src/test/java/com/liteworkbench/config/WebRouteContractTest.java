package com.liteworkbench.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.ParameterizableViewController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 路由契约：接口兜底与前端深链兜底的顺序与命中范围，顺序错一位就会把接口当成页面返回 HTML */
class WebRouteContractTest {

    private final WebConfig config = new WebConfig();
    private final SimpleUrlHandlerMapping apiMapping = initialized(config.apiFallbackHandlerMapping());
    private final SimpleUrlHandlerMapping spaMapping = initialized(config.spaFallbackHandlerMapping());

    /** 手工构造的映射必须补上容器里的初始化回调，否则内部 pattern 表为空、一律返回 null */
    private static SimpleUrlHandlerMapping initialized(SimpleUrlHandlerMapping mapping) {
        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();
        mapping.setApplicationContext(context);
        return mapping;
    }

    @Test
    @DisplayName("接口兜底先于深链兜底生效，未命中接口的 /api 路径回 404 信封")
    void apiFallbackPrecedesSpaAndReturnsEnvelope() throws Exception {
        assertTrue(apiMapping.getOrder() < spaMapping.getOrder(), "接口兜底必须先于深链兜底");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/logs/page");
        HandlerExecutionChain chain = apiMapping.getHandler(request);
        assertNotNull(chain, "未命中接口的 /api 路径必须由接口兜底接管");

        MockHttpServletResponse response = new MockHttpServletResponse();
        ((HttpRequestHandler) chain.getHandler()).handleRequest(request, response);
        assertEquals(404, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":\"1002\""),
                "兜底响应必须是统一信封：" + response.getContentAsString());

        assertNull(apiMapping.getHandler(new MockHttpServletRequest("GET", "/dashboard")),
                "深链路径不能被接口兜底截走");
    }

    @Test
    @DisplayName("深链兜底只覆盖不含点的前端路由，带点静态资源交给静态资源处理器")
    void spaFallbackCoversRoutesOnly() throws Exception {
        assertEquals("forward:/index.html", viewNameOf("/dashboard"));
        assertEquals("forward:/index.html", viewNameOf("/system/user"));
        assertNull(spaMapping.getHandler(new MockHttpServletRequest("GET", "/assets/index-abc123.js")),
                "静态资源必须留给静态资源处理器");
    }

    @Test
    @DisplayName("深链兜底模式本身也匹配 /api 路径，两者隔离完全依赖映射顺序")
    void spaPatternOverlapsApiPathByDesign() throws Exception {
        assertNotNull(spaMapping.getHandler(new MockHttpServletRequest("GET", "/api/v1/auth/login")),
                "深链兜底模式确实覆盖 /api，故顺序断言是接口语义的唯一保障");
    }

    private String viewNameOf(String path) throws Exception {
        HandlerExecutionChain chain = spaMapping.getHandler(new MockHttpServletRequest("GET", path));
        assertNotNull(chain, path + " 应命中深链兜底");
        return ((ParameterizableViewController) chain.getHandler()).getViewName();
    }
}
