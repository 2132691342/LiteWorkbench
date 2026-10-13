package com.liteworkbench.config;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.domain.constant.PermConst;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 权限契约：api 层注解与 PermConst 声明的权限点必须全部落进菜单种子，防止授权漂移 */
class PermissionContractTest {

    private static final String API_PACKAGE = "com.liteworkbench.api";

    @Test
    void 控制器注解与权限常量应全部存在于菜单种子() throws Exception {
        List<String> seeds = DataInitializer.seedPermCodes();
        TreeSet<String> missing = new TreeSet<>();

        List<Class<?>> controllers = controllerClasses();
        assertFalse(controllers.isEmpty(), "未扫描到 " + API_PACKAGE + " 下的类，扫描逻辑失效");

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

    /** 直接扫 api 包，新增控制器无需登记清单 */
    private List<Class<?>> controllerClasses() throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        Enumeration<URL> resources = getClass().getClassLoader().getResources(API_PACKAGE.replace('.', '/'));
        while (resources.hasMoreElements()) {
            Path dir = Paths.get(resources.nextElement().toURI());
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter((item) -> item.getFileName().toString().endsWith(".class")).toList()) {
                    String simpleName = file.getFileName().toString().replace(".class", "");
                    classes.add(Class.forName(API_PACKAGE + "." + simpleName));
                }
            }
        }
        return classes;
    }

    private void collect(SaCheckPermission[] annotations, List<String> seeds, TreeSet<String> missing) {
        for (SaCheckPermission annotation : annotations) {
            for (String value : annotation.value()) {
                if (Objects.nonNull(value) && !seeds.contains(value)) {
                    missing.add(value);
                }
            }
        }
    }
}
