package com.liteworkbench.config;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.domain.constant.PermConst;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.ParameterizableViewController;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态契约：三处「一处改了另一处没跟上」的漂移，编译期看不见，只会在运行到那条路径时炸。
 * 一个用例答一个契约，全部只读 classpath 与源码声明，不依赖中间件与 Spring 容器。
 */
class ContractTest {

    /** ============ 权限契约 ============ */

    /** 权限点在三处声明：控制器注解、PermConst 常量、菜单种子；任一处漏改都会导致 403 或菜单不可见 */
    @Test
    @DisplayName("控制器注解与 PermConst 声明的权限点必须全部落进菜单种子")
    void permissionCodesAllExistInMenuSeeds() throws Exception {
        List<String> seeds = DataInitializer.seedPermCodes();
        TreeSet<String> missing = new TreeSet<>();

        List<Class<?>> controllers = scanClasses("com/liteworkbench/api/*.class");
        assertFalse(controllers.isEmpty(), "未扫描到 api 包下的类，扫描逻辑失效");

        for (Class<?> controller : controllers) {
            collect(controller.getAnnotationsByType(SaCheckPermission.class), seeds, missing);
            for (Method method : controller.getDeclaredMethods()) {
                collect(method.getAnnotationsByType(SaCheckPermission.class), seeds, missing);
            }
        }
        for (Field field : PermConst.class.getDeclaredFields()) {
            if (field.getType() == String.class) {
                String value = (String) field.get(null);
                if (!seeds.contains(value)) {
                    missing.add(value);
                }
            }
        }
        assertTrue(missing.isEmpty(), "以下权限点未进菜单种子: " + missing);
    }

    private void collect(SaCheckPermission[] annotations, List<String> seeds, TreeSet<String> missing) {
        for (SaCheckPermission annotation : annotations) {
            for (String value : annotation.value()) {
                if (!seeds.contains(value)) {
                    missing.add(value);
                }
            }
        }
    }

    /** ============ Mapper 契约 ============ */

    /** XML 的 namespace、statement id、resultType 三样都只在首次调用时校验，解析一遍即可全部左移到这里 */
    @Test
    @DisplayName("Mapper XML 应与接口对齐并可被 MyBatis 完整解析")
    void mapperXmlBindsToInterfaces() throws Exception {
        Resource[] xmlFiles = new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml");
        assertTrue(xmlFiles.length > 0, "未找到任何 Mapper XML");

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        // 先注册接口，解析时才会暴露重名、namespace 不对、resultType 解析不了等问题
        configuration.addMappers("com.liteworkbench.mapper");

        for (Resource resource : xmlFiles) {
            try (InputStream input = resource.getInputStream()) {
                new XMLMapperBuilder(input, configuration, Objects.toString(resource.getFilename(), "mapper"),
                        configuration.getSqlFragments()).parse();
            } catch (Exception e) {
                throw new AssertionError("Mapper XML 解析失败：" + resource.getFilename() + " → " + e.getMessage(), e);
            }
        }
    }

    /** ============ 路由契约 ============ */

    private final WebConfig config = new WebConfig();
    private final SimpleUrlHandlerMapping apiMapping = initialized(config.apiFallbackHandlerMapping());
    private final SimpleUrlHandlerMapping spaMapping = initialized(config.spaFallbackHandlerMapping());

    /** 深链兜底模式本身也覆盖 /api，两者隔离完全依赖映射顺序，顺序错一位接口就会返回 HTML */
    @Test
    @DisplayName("接口兜底先于深链兜底：未命中接口的 /api 回 404 信封，深链与带点资源各归其位")
    void apiFallbackPrecedesSpaAndKeepsEnvelope() throws Exception {
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
        assertEquals("forward:/index.html", viewNameOf("/system/user"));
        assertNull(spaMapping.getHandler(new MockHttpServletRequest("GET", "/assets/index-abc123.js")),
                "带点的静态资源必须留给静态资源处理器");
    }

    /** 手工构造的映射必须补上容器里的初始化回调，否则内部 pattern 表为空、一律返回 null */
    private static SimpleUrlHandlerMapping initialized(SimpleUrlHandlerMapping mapping) {
        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();
        mapping.setApplicationContext(context);
        return mapping;
    }

    private String viewNameOf(String path) throws Exception {
        HandlerExecutionChain chain = spaMapping.getHandler(new MockHttpServletRequest("GET", path));
        assertNotNull(chain, path + " 应命中深链兜底");
        return ((ParameterizableViewController) chain.getHandler()).getViewName();
    }

    /** ============ 公共：按 classpath 模式扫描类 ============ */

    /** 走 classpath* 模式而非目录列举：打包成 JAR 后目录列举会失效 */
    private List<Class<?>> scanClasses(String pattern) throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:" + pattern);
        List<Class<?>> classes = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = Objects.toString(resource.getFilename(), "");
            if (filename.endsWith(".class")) {
                classes.add(load("com.liteworkbench.api." + filename.replace(".class", "")));
            }
        }
        return classes;
    }

    private Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("classpath 扫描到的类无法加载：" + className, e);
        }
    }
}
