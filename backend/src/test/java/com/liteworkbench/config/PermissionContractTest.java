package com.liteworkbench.config;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.domain.constant.PermConst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨模块契约：权限点在「控制器注解 / PermConst / 菜单种子 / 前端引用」四处声明必须互相落得住。
 * 任一处漏写都只在运行到那个功能时才暴露，且报错点离成因很远，因此在测试期一次查清。
 * 两个用例分别覆盖后端三处自洽、后端声明与前端引用对齐。
 */
class PermissionContractTest {

    /** 权限标识形态：域:资源:动作 */
    private static final Pattern PERM_SHAPE = Pattern.compile("^[a-z][a-z0-9]*:[a-z][a-z0-9]*:[a-z][a-z0-9]*$");

    /**
     * 菜单种子一条记录，列序为 seq、parent、名称、类型、路由、组件、图标、权限点、排序。
     * NULL 与引号值都吃，分组 1..9 与列一一对应。
     */
    private static final Pattern SEED_ROW = Pattern.compile(
            "\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*,"
                    + "\\s*(?:NULL|'([^']*)')\\s*,\\s*(?:NULL|'([^']*)')\\s*,"
                    + "\\s*(?:NULL|'([^']*)')\\s*,\\s*(?:NULL|'([^']*)')\\s*,\\s*(\\d+)\\s*\\)");

    /** lw_menu_seed 的 INSERT 语句块：契约判定只看这一段，脚本其他位置的引号值不参与 */
    private static final Pattern SEED_BLOCK = Pattern.compile("INSERT INTO lw_menu_seed.*?;", Pattern.DOTALL);

    /** 前端权限引用：hasPerm('x:y:z') 与 v-perm="'x:y:z'" */
    private static final Pattern FRONTEND_PERM = Pattern.compile(
            "(?:hasPerm|v-perm)[\\s(=]*['\"]([a-z][a-z0-9]*:[a-z][a-z0-9]*:[a-z][a-z0-9]*)['\"]");

    @Test
    @DisplayName("后端自洽：控制器注解与 PermConst 声明的权限点必须全部落进菜单种子，且种子不写额外权限点")
    void permissionCodesAllExistInMenuSeeds() throws Exception {
        List<Class<?>> controllers = scanControllers();
        assertFalse(controllers.isEmpty(), "未扫描到 api 包下的类，扫描逻辑失效");

        TreeSet<String> annotated = new TreeSet<>();
        for (Class<?> controller : controllers) {
            for (SaCheckPermission annotation : controller.getAnnotationsByType(SaCheckPermission.class)) {
                annotated.addAll(List.of(annotation.value()));
            }
            for (Method method : controller.getDeclaredMethods()) {
                for (SaCheckPermission annotation : method.getAnnotationsByType(SaCheckPermission.class)) {
                    annotated.addAll(List.of(annotation.value()));
                }
            }
        }

        TreeSet<String> constants = permConstValues();
        TreeSet<String> seeded = seedPermCodes();

        // 正向：注解与常量声明的每一个权限点都要有种子行，否则没人能被授予
        TreeSet<String> ungranted = new TreeSet<>(annotated);
        ungranted.addAll(constants);
        ungranted.removeAll(seeded);
        assertTrue(ungranted.isEmpty(), "以下权限点未进菜单种子，任何角色都拿不到: " + ungranted);

        // 反向：种子里不得出现 PermConst 之外的权限点，否则控制器不认、前端也无从判定
        TreeSet<String> orphan = new TreeSet<>(seeded);
        orphan.removeAll(constants);
        assertTrue(orphan.isEmpty(), "菜单种子写了 PermConst 之外的权限点: " + orphan);

        // 注解里用到的权限点必须都在常量表里，禁止在注解里裸写字面量
        TreeSet<String> bare = new TreeSet<>(annotated);
        bare.removeAll(constants);
        assertTrue(bare.isEmpty(), "控制器注解裸写了权限点字面量（应引用 PermConst）: " + bare);
    }

    @Test
    @DisplayName("前后端对齐：前端引用的权限点已声明，菜单种子的视图文件存在")
    void frontendRefsResolveToBackendDeclarations() throws Exception {
        Path frontend = frontendSrc();
        TreeSet<String> declared = permConstValues();

        TreeSet<String> unknownPerms = new TreeSet<>();
        int scanned = 0;
        try (Stream<Path> walk = Files.walk(frontend)) {
            List<Path> sources = walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".vue") || name.endsWith(".ts");
                    })
                    .toList();
            for (Path file : sources) {
                scanned++;
                Matcher matcher = FRONTEND_PERM.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    if (!declared.contains(matcher.group(1))) {
                        unknownPerms.add(matcher.group(1) + " ← " + frontend.relativize(file));
                    }
                }
            }
        }
        assertTrue(scanned > 50, "前端源码文件数异常（" + scanned + "），扫描范围失效");

        TreeSet<String> missingViews = new TreeSet<>();
        for (String component : seedComponents()) {
            if (!Files.isRegularFile(frontend.resolve("views").resolve(component + ".vue"))) {
                missingViews.add(component);
            }
        }

        assertTrue(unknownPerms.isEmpty(), "前端引用了 PermConst 之外的权限点（按钮对所有人永不显示）: " + unknownPerms);
        assertTrue(missingViews.isEmpty(), "菜单种子指向了不存在的视图（动态路由注册不上，点开即 404）: " + missingViews);
    }

    /** PermConst 声明的全部权限点 */
    private TreeSet<String> permConstValues() throws IllegalAccessException {
        TreeSet<String> values = new TreeSet<>();
        for (Field field : PermConst.class.getDeclaredFields()) {
            if (field.getType() == String.class) {
                values.add((String) field.get(null));
            }
        }
        return values;
    }

    /** 菜单种子里声明的全部权限点，顺带校验形态 */
    private TreeSet<String> seedPermCodes() throws IOException {
        TreeSet<String> perms = new TreeSet<>();
        for (String[] row : seedRows()) {
            if (Objects.isNull(row[7])) {
                continue;
            }
            if (!PERM_SHAPE.matcher(row[7]).matches()) {
                throw new AssertionError("菜单种子权限点形态不合法: " + row[7]);
            }
            perms.add(row[7]);
        }
        assertFalse(perms.isEmpty(), "未从菜单种子解析到权限点，脚本结构或解析规则失效");
        return perms;
    }

    /** 菜单种子里页面节点声明的视图组件，须能在 frontend/src/views 下命中同名文件 */
    private TreeSet<String> seedComponents() throws IOException {
        TreeSet<String> components = new TreeSet<>();
        for (String[] row : seedRows()) {
            if ("menu".equals(row[3])) {
                assertTrue(Objects.nonNull(row[5]), "页面节点缺少视图组件: " + row[2]);
                components.add(row[5]);
            }
        }
        assertFalse(components.isEmpty(), "未从菜单种子解析到视图组件，脚本结构或解析规则失效");
        return components;
    }

    /** 解析 lw_menu_seed 的 VALUES 行，返回 seq / parent / 名称 / 类型 / 路由 / 组件 / 图标 / 权限点 / 排序 */
    private List<String[]> seedRows() throws IOException {
        String sql = StreamUtils.copyToString(
                new ClassPathResource("db/data.sql").getInputStream(), StandardCharsets.UTF_8);
        Matcher block = SEED_BLOCK.matcher(sql);
        assertTrue(block.find(), "未定位到 lw_menu_seed 种子块，脚本结构或解析规则失效");
        String seed = block.group();

        List<String[]> rows = new ArrayList<>();
        Matcher row = SEED_ROW.matcher(seed);
        while (row.find()) {
            rows.add(new String[]{row.group(1), row.group(2), row.group(3), row.group(4), row.group(5),
                    row.group(6), row.group(7), row.group(8), row.group(9)});
        }
        // 种子行以「行首左括号 + 数字」起头：行数与解析结果数必须相等，否则存在被静默跳过的行形状
        long started = 0;
        for (String line : seed.split("\r?\n")) {
            if (line.stripLeading().matches("\\(\\s*\\d+.*")) {
                started++;
            }
        }
        assertEquals(started, rows.size(), "菜单种子行首数与解析行数不一致，存在解析不了的行形状");
        assertFalse(rows.isEmpty(), "菜单种子为空，脚本结构或解析规则失效");
        return rows;
    }

    /** 前端源码根：Maven 从 backend 目录起算，IDE 常从仓库根起算，两种都试 */
    private Path frontendSrc() {
        Path base = Path.of(System.getProperty("user.dir"));
        List<Path> candidates = List.of(base.resolve("frontend/src"), base.getParent().resolve("frontend/src"));
        return candidates.stream().filter(Files::isDirectory).findFirst()
                .orElseThrow(() -> new AssertionError("找不到 frontend/src，契约测试无法核对前端引用"));
    }

    /** 走 classpath* 模式而非目录列举：打包成 JAR 后目录列举会失效 */
    private List<Class<?>> scanControllers() throws IOException {
        List<Class<?>> classes = new ArrayList<>();
        for (org.springframework.core.io.Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/liteworkbench/api/*.class")) {
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
