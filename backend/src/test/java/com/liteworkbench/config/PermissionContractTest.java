package com.liteworkbench.config;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.domain.constant.PermConst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限契约：控制器注解、PermConst 常量、菜单种子三处声明必须一致。
 * 三处中漏任何一处，接口只在运行到该功能时才暴露，契约测试在编译期兜住。
 */
class PermissionContractTest {

    /** 权限标识形态：域:资源:动作；带引号匹配，避免把种子脚本里的 LIKE 'system:role:%' 当成权限点 */
    private static final Pattern PERM_PATTERN =
            Pattern.compile("'([a-z][a-z0-9]*:[a-z][a-z0-9]*:[a-z][a-z0-9]*)'");

    @Test
    @DisplayName("控制器注解与 PermConst 声明的权限点必须全部落进菜单种子")
    void permissionCodesAllExistInMenuSeeds() throws Exception {
        List<String> seeds = seedPermCodes();
        TreeSet<String> missing = new TreeSet<>();

        List<Class<?>> controllers = scanControllers();
        assertFalse(controllers.isEmpty(), "未扫描到 api 包下的类，扫描逻辑失效");

        for (Class<?> controller : controllers) {
            collect(controller.getAnnotationsByType(SaCheckPermission.class), seeds, missing);
            for (Method method : controller.getDeclaredMethods()) {
                collect(method.getAnnotationsByType(SaCheckPermission.class), seeds, missing);
            }
        }
        for (Field field : PermConst.class.getDeclaredFields()) {
            if (field.getType() == String.class) {
                collectValue((String) field.get(null), seeds, missing);
            }
        }
        assertTrue(missing.isEmpty(), "以下权限点未进菜单种子: " + missing);
    }

    private void collect(SaCheckPermission[] annotations, List<String> seeds, TreeSet<String> missing) {
        for (SaCheckPermission annotation : annotations) {
            for (String value : annotation.value()) {
                collectValue(value, seeds, missing);
            }
        }
    }

    private void collectValue(String value, List<String> seeds, TreeSet<String> missing) {
        if (!seeds.contains(value)) {
            missing.add(value);
        }
    }

    /** 菜单种子落在 db/data.sql，权限点从脚本里解析，避免脚本与 Java 常量两处清单漂移 */
    private List<String> seedPermCodes() throws IOException {
        Resource resource = new ClassPathResource("db/data.sql");
        String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        TreeSet<String> perms = new TreeSet<>();
        Matcher matcher = PERM_PATTERN.matcher(sql);
        while (matcher.find()) {
            perms.add(matcher.group(1));
        }
        assertFalse(perms.isEmpty(), "未从 db/data.sql 解析到权限点，种子脚本或解析规则失效");
        return new ArrayList<>(perms);
    }

    /** 走 classpath* 模式而非目录列举：打包成 JAR 后目录列举会失效 */
    private List<Class<?>> scanControllers() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/liteworkbench/api/*.class");
        List<Class<?>> classes = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = Objects.toString(resource.getFilename(), "");
            if (!filename.endsWith(".class")) {
                continue;
            }
            String className = "com.liteworkbench.api." + filename.replace(".class", "");
            try {
                classes.add(Class.forName(className));
            } catch (ClassNotFoundException e) {
                throw new AssertionError("classpath 扫描到的类无法加载：" + className, e);
            }
        }
        return classes;
    }
}
