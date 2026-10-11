package com.liteworkbench.config;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.api.AuthController;
import com.liteworkbench.api.FileController;
import com.liteworkbench.api.MenuController;
import com.liteworkbench.api.MonitorController;
import com.liteworkbench.api.ProviderController;
import com.liteworkbench.api.RoleController;
import com.liteworkbench.api.RunController;
import com.liteworkbench.api.SessionController;
import com.liteworkbench.api.SkillController;
import com.liteworkbench.api.ToolController;
import com.liteworkbench.api.UserController;
import com.liteworkbench.domain.constant.PermConst;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 权限契约：控制器注解与常量声明的权限点必须全部存在于菜单种子，防止授权漂移 */
class PermissionContractTest {

    /** 控制器清单：新增控制器时同步登记 */
    private static final List<Class<?>> CONTROLLERS = List.of(
            AuthController.class, UserController.class, RoleController.class, MenuController.class,
            SessionController.class, RunController.class, ToolController.class, SkillController.class,
            FileController.class, ProviderController.class, MonitorController.class);

    @Test
    void controllerPermissionsShouldExistInMenuSeeds() {
        List<String> seeds = DataInitializer.seedPermCodes();
        TreeSet<String> missing = new TreeSet<>();
        for (Class<?> controller : CONTROLLERS) {
            collectTypePermissions(controller.getAnnotationsByType(SaCheckPermission.class), seeds, missing);
            for (Method method : controller.getDeclaredMethods()) {
                collectTypePermissions(method.getAnnotationsByType(SaCheckPermission.class), seeds, missing);
            }
        }
        assertTrue(missing.isEmpty(), "控制器使用了菜单种子未声明的权限点: " + missing);
    }

    @Test
    void permConstShouldExistInMenuSeeds() throws IllegalAccessException {
        List<String> seeds = DataInitializer.seedPermCodes();
        TreeSet<String> missing = new TreeSet<>();
        for (Field field : PermConst.class.getDeclaredFields()) {
            if (field.getType() == String.class) {
                String value = (String) field.get(null);
                if (!seeds.contains(value)) {
                    missing.add(value);
                }
            }
        }
        assertTrue(missing.isEmpty(), "PermConst 声明的权限点未进菜单种子: " + missing);
    }

    private void collectTypePermissions(SaCheckPermission[] annotations, List<String> seeds,
                                        TreeSet<String> missing) {
        for (SaCheckPermission annotation : annotations) {
            for (String value : annotation.value()) {
                if (Objects.nonNull(value) && !seeds.contains(value)) {
                    missing.add(value);
                }
            }
        }
    }
}
