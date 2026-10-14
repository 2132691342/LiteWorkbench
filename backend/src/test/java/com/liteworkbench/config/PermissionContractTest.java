package com.liteworkbench.config;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.domain.constant.PermConst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 权限契约：控制器注解、PermConst 常量、菜单种子三处声明必须一致，漏一处只在运行到该接口时才暴露 */
class PermissionContractTest {

    @Test
    @DisplayName("控制器注解与 PermConst 声明的权限点必须全部落进菜单种子")
    void permissionCodesAllExistInMenuSeeds() throws Exception {
        List<String> seeds = DataInitializer.seedPermCodes();
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
