package com.liteworkbench.config;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.PasswordUtil;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.MenuTypeEnum;
import com.liteworkbench.domain.constant.RoleCodeConst;
import com.liteworkbench.domain.constant.StateEnum;
import com.liteworkbench.domain.constant.UserStateEnum;
import com.liteworkbench.domain.entity.SysMenuDO;
import com.liteworkbench.domain.entity.SysRoleDO;
import com.liteworkbench.domain.entity.SysUserDO;
import com.liteworkbench.domain.entity.WbProviderDO;
import com.liteworkbench.mapper.SysMenuMapper;
import com.liteworkbench.mapper.SysRoleMapper;
import com.liteworkbench.mapper.SysUserMapper;
import com.liteworkbench.mapper.WbProviderMapper;
import com.liteworkbench.service.PromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 种子数据：内置角色、种子账号、菜单树、默认供应方，全部幂等写入 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    /** 管理员登录名 */
    private static final String ADMIN_USERNAME = "admin";
    /** 演示用户登录名 */
    private static final String DEMO_USERNAME = "demo";

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysMenuMapper sysMenuMapper;
    private final WbProviderMapper providerMapper;
    private final PromptService promptService;
    private final AuthProperties authProperties;

    /** 启动时补齐种子数据 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        long start = System.currentTimeMillis();
        initMenus();
        initRolesAndUsers();
        initProviders();
        grantNewMenusToAdminRoles();
        initBuiltinPrompts();
        log.info("[data.init] cost={}ms", System.currentTimeMillis() - start);
    }

    /** 菜单逐条补齐：ID 固定便于授权引用，已存在则跳过，支持升级时新增菜单 */
    private void initMenus() {
        int created = 0;
        for (MenuSeed seed : MENU_SEEDS) {
            String menuId = menuId(seed.seq());
            if (Objects.nonNull(sysMenuMapper.selectById(menuId))) {
                continue;
            }
            SysMenuDO menu = new SysMenuDO();
            menu.setMenuId(menuId);
            menu.setParentId(seed.parentSeq() == 0 ? "0" : menuId(seed.parentSeq()));
            menu.setMenuName(seed.name());
            menu.setMenuType(seed.type());
            menu.setRoutePath(seed.route());
            menu.setComponent(seed.component());
            menu.setIcon(seed.icon());
            menu.setPermCode(seed.perm());
            menu.setSortNo(seed.sort());
            menu.setVisible(true);
            menu.setState(StateEnum.NORMAL.getCode());
            menu.setCreatedBy("system");
            sysMenuMapper.insert(menu);
            created++;
        }
        log.info("[data.init.menu] created={} total={}", created, MENU_SEEDS.size());
    }

    /** 内置三角色 + 种子账号：admin（超级管理员）、demo（普通用户） */
    private void initRolesAndUsers() {
        if (Objects.nonNull(sysUserMapper.selectByUsername(ADMIN_USERNAME))) {
            return;
        }
        List<SysMenuDO> menus = sysMenuMapper.selectAll();
        List<String> allMenuIds = menus.stream().map(SysMenuDO::getMenuId).toList();

        String superRoleId = createRole(RoleCodeConst.SUPER_ADMIN, "超级管理员", 0);
        grantMenus(superRoleId, allMenuIds);
        createUser(ADMIN_USERNAME, "超级管理员", superRoleId);

        String adminRoleId = createRole(RoleCodeConst.ADMIN, "平台管理员", 1);
        grantMenus(adminRoleId, allMenuIds);

        String userRoleId = createRole(RoleCodeConst.USER, "普通用户", 2);
        List<String> userMenuIds = menus.stream()
                .filter(menu -> CollUtil.contains(USER_MENU_NAMES, menu.getMenuName()))
                .map(SysMenuDO::getMenuId)
                .toList();
        grantMenus(userRoleId, userMenuIds);
        createUser(DEMO_USERNAME, "演示用户", userRoleId);
        log.info("[data.init.roles] super={} admin={} user={}", superRoleId, adminRoleId, userRoleId);
    }

    /** 默认供应方：本地 Ollama，开箱可测；OpenAI / Anthropic 由管理员在页面添加 */
    private void initProviders() {
        if (Objects.nonNull(providerMapper.selectByCode("ollama"))) {
            return;
        }
        WbProviderDO provider = new WbProviderDO();
        provider.setProviderId(IdGenerator.generate(IdPrefixConst.PROVIDER));
        provider.setProviderCode("ollama");
        provider.setProviderName("Ollama 本地模型");
        provider.setBaseUrl("http://localhost:11434");
        provider.setDefaultModel("qwen2.5:7b");
        provider.setEnabled(true);
        provider.setCreatedBy("system");
        provider.setRemark("默认供应方，需本机 11434 端口运行 Ollama；生产环境请在设置页改为实际端点");
        providerMapper.insert(provider);
        log.info("[data.init.provider] code=ollama");
    }

    private String createRole(String roleCode, String roleName, int sortNo) {
        SysRoleDO role = new SysRoleDO();
        role.setRoleId(IdGenerator.generate(IdPrefixConst.ROLE));
        role.setRoleCode(roleCode);
        role.setRoleName(roleName);
        role.setSortNo(sortNo);
        role.setState(StateEnum.NORMAL.getCode());
        role.setCreatedBy("system");
        sysRoleMapper.insert(role);
        return role.getRoleId();
    }

    private void grantMenus(String roleId, List<String> menuIds) {
        if (CollUtil.isNotEmpty(menuIds)) {
            sysRoleMapper.insertRoleMenus(roleId, menuIds, "system");
        }
    }

    private void createUser(String username, String nickname, String roleId) {
        String salt = PasswordUtil.randomSalt();
        SysUserDO user = new SysUserDO();
        user.setUserId(IdGenerator.generate(IdPrefixConst.USER));
        user.setUsername(username);
        user.setPasswordSalt(salt);
        user.setPasswordHash(PasswordUtil.hash(authProperties.getDefaultPassword(), salt));
        user.setNickname(nickname);
        user.setState(UserStateEnum.NORMAL.getCode());
        user.setCreatedBy("system");
        user.setRemark("种子账号，首次登录后请修改密码");
        sysUserMapper.insert(user);
        sysUserMapper.insertUserRoles(user.getUserId(), List.of(roleId), "system");
    }

    /** 升级补齐：管理角色始终持有全部菜单，新增菜单无需手工重新授权 */
    private void grantNewMenusToAdminRoles() {
        List<String> allMenuIds = sysMenuMapper.selectAll().stream().map(SysMenuDO::getMenuId).toList();
        if (CollUtil.isEmpty(allMenuIds)) {
            return;
        }
        List<String> userMenuIds = sysMenuMapper.selectAll().stream()
                .filter(menu -> CollUtil.contains(USER_MENU_NAMES, menu.getMenuName()))
                .map(SysMenuDO::getMenuId)
                .toList();
        for (SysRoleDO role : sysRoleMapper.selectList()) {
            boolean admin = RoleCodeConst.SUPER_ADMIN.equals(role.getRoleCode())
                    || RoleCodeConst.ADMIN.equals(role.getRoleCode());
            boolean normalUser = RoleCodeConst.USER.equals(role.getRoleCode());
            if (!admin && !normalUser) {
                continue;
            }
            List<String> target = admin ? allMenuIds : userMenuIds;
            List<String> owned = sysRoleMapper.selectMenuIdsByRoleId(role.getRoleId());
            List<String> missing = target.stream().filter(id -> !owned.contains(id)).toList();
            if (CollUtil.isNotEmpty(missing)) {
                sysRoleMapper.insertRoleMenus(role.getRoleId(), missing, "system");
                log.info("[data.init.grant] role={} 补齐菜单 {}", role.getRoleCode(), missing.size());
            }
        }
    }

    /** 内置提示词模板：固定 ID 幂等写入；这是可降级数据，失败只记 error，不阻断启动 */
    private void initBuiltinPrompts() {
        try {
            promptService.initBuiltin(BUILTIN_PROMPTS);
        } catch (Exception e) {
            log.error("[data.init.prompt] 内置提示词初始化失败，已跳过 {}", e.getMessage());
        }
    }

    private String menuId(int seq) {
        return String.format("%s_%026d", IdPrefixConst.MENU.getPrefix(), seq);
    }

    /** 菜单种子中的全部权限点，供权限契约测试校验 */
    static List<String> seedPermCodes() {
        return MENU_SEEDS.stream()
                .map(MenuSeed::perm)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }

    /** 普通用户可用的菜单与操作点：工作台功能 + 自己数据的增删改 */
    private static final List<String> USER_MENU_NAMES = List.of(
            "menu.dashboard", "menu.console", "menu.files", "menu.skills", "menu.kb", "menu.settings", "menu.showcase",
            "menu.writer", "menu.prompt", "menu.flow",
            "menu.writer.list", "menu.writer.create", "menu.writer.update", "menu.writer.delete", "menu.writer.search",
            "menu.prompt.list", "menu.prompt.create", "menu.prompt.update", "menu.prompt.delete",
            "menu.flow.list", "menu.flow.create", "menu.flow.update", "menu.flow.delete", "menu.flow.run",
            "menu.files.delete",
            "menu.skills.list", "menu.skills.create", "menu.skills.update", "menu.skills.delete",
            "menu.kb.list", "menu.kb.create", "menu.kb.update", "menu.kb.delete", "menu.kb.upload", "menu.kb.search");

    /** 菜单种子：seq 为固定自增号，parentSeq 为 0 表示根 */
    private record MenuSeed(int seq, int parentSeq, String name, String type, String route,
                            String component, String icon, String perm, int sort) {
    }

    /** 内置提示词种子：固定 ID 幂等写入，ID 形如 PRPT_ + 24 位序号 */
    private static final List<PromptService.BuiltinSeed> BUILTIN_PROMPTS = List.of(
            new PromptService.BuiltinSeed(promptId(1), "文章大纲", "写作",
                    "请围绕《{{topic}}》产出一篇{{style}}风格的文章大纲，包含 5 到 8 个一级标题与关键论点。",
                    "[{\"name\":\"topic\",\"label\":\"主题\",\"defaultValue\":\"\"},"
                            + "{\"name\":\"style\",\"label\":\"风格\",\"defaultValue\":\"专业简洁\"}]"),
            new PromptService.BuiltinSeed(promptId(2), "标题构思", "写作",
                    "请为下面这篇内容构思 10 个候选标题，要求有信息量、不标题党：\n{{content}}",
                    "[{\"name\":\"content\",\"label\":\"正文\",\"defaultValue\":\"\"}]"),
            new PromptService.BuiltinSeed(promptId(3), "润色改写", "改写",
                    "请按{{tone}}的语气润色下面这段内容，保持原意，改善节奏与用词：\n{{content}}",
                    "[{\"name\":\"tone\",\"label\":\"语气\",\"defaultValue\":\"专业\"},"
                            + "{\"name\":\"content\",\"label\":\"正文\",\"defaultValue\":\"\"}]"),
            new PromptService.BuiltinSeed(promptId(4), "要点摘要", "摘要",
                    "请为下面这段内容生成不超过 {{limit}} 字的摘要，保留关键结论：\n{{content}}",
                    "[{\"name\":\"limit\",\"label\":\"字数\",\"defaultValue\":\"200\"},"
                            + "{\"name\":\"content\",\"label\":\"正文\",\"defaultValue\":\"\"}]"),
            new PromptService.BuiltinSeed(promptId(5), "中英互译", "翻译",
                    "请把下面这段内容翻译为{{language}}，保持术语一致与段落结构：\n{{content}}",
                    "[{\"name\":\"language\",\"label\":\"目标语言\",\"defaultValue\":\"English\"},"
                            + "{\"name\":\"content\",\"label\":\"正文\",\"defaultValue\":\"\"}]"),
            new PromptService.BuiltinSeed(promptId(6), "代码讲解", "代码",
                    "请解释下面这段 {{lang}} 代码的职责、关键路径与潜在风险：\n```\n{{code}}\n```",
                    "[{\"name\":\"lang\",\"label\":\"语言\",\"defaultValue\":\"Python\"},"
                            + "{\"name\":\"code\",\"label\":\"代码\",\"defaultValue\":\"\"}]"));

    private static String promptId(int seq) {
        return String.format("%s_%026d", IdPrefixConst.PROMPT.getPrefix(), seq);
    }

    private static final List<MenuSeed> MENU_SEEDS = buildSeeds();

    private static List<MenuSeed> buildSeeds() {
        List<MenuSeed> seeds = new ArrayList<>();
        String menu = MenuTypeEnum.MENU.getCode();
        String dir = MenuTypeEnum.DIR.getCode();
        String button = MenuTypeEnum.BUTTON.getCode();
        // 工作台主菜单
        seeds.add(new MenuSeed(1, 0, "menu.dashboard", menu, "/dashboard", "Dashboard", "dashboard", null, 10));
        seeds.add(new MenuSeed(2, 0, "menu.console", menu, "/console", "console/Console", "chat", null, 20));
        seeds.add(new MenuSeed(3, 0, "menu.files", menu, "/files", "files/FileList", "folder", null, 30));
        seeds.add(new MenuSeed(4, 0, "menu.skills", menu, "/skills", "skills/SkillList", "bulb", null, 40));
        seeds.add(new MenuSeed(45, 0, "menu.kb", menu, "/kb", "kb/KbCenter", "book", null, 45));
        seeds.add(new MenuSeed(46, 0, "menu.writer", menu, "/writer", "writer/WriterStudio", "edit", null, 35));
        seeds.add(new MenuSeed(47, 0, "menu.prompt", menu, "/prompt", "prompts/PromptLibrary", "magic", null, 36));
        seeds.add(new MenuSeed(48, 0, "menu.flow", menu, "/flow", "flow/FlowCanvas", "connection", null, 37));
        seeds.add(new MenuSeed(5, 0, "menu.logs", menu, "/logs", "logs/LogSearch", "document", "monitor:log:list", 50));
        seeds.add(new MenuSeed(6, 0, "menu.monitor", menu, "/monitor", "monitor/HealthPanel", "pulse", "monitor:health:list", 60));
        seeds.add(new MenuSeed(7, 0, "menu.showcase", menu, "/showcase", "showcase/Showcase", "palette", null, 70));
        seeds.add(new MenuSeed(8, 0, "menu.settings", menu, "/settings", "settings/Settings", "gear", null, 80));
        // 系统管理
        seeds.add(new MenuSeed(10, 0, "menu.system", dir, "/system", null, "setting", null, 90));
        seeds.add(new MenuSeed(11, 10, "menu.system.user", menu, "/system/user", "system/UserList", "user", "system:user:list", 91));
        seeds.add(new MenuSeed(12, 11, "menu.system.user.create", button, null, null, null, "system:user:create", 1));
        seeds.add(new MenuSeed(13, 11, "menu.system.user.update", button, null, null, null, "system:user:update", 2));
        seeds.add(new MenuSeed(14, 11, "menu.system.user.delete", button, null, null, null, "system:user:delete", 3));
        seeds.add(new MenuSeed(15, 11, "menu.system.user.reset", button, null, null, null, "system:user:reset", 4));
        seeds.add(new MenuSeed(20, 10, "menu.system.role", menu, "/system/role", "system/RoleList", "shield", "system:role:list", 92));
        seeds.add(new MenuSeed(21, 20, "menu.system.role.create", button, null, null, null, "system:role:create", 1));
        seeds.add(new MenuSeed(22, 20, "menu.system.role.update", button, null, null, null, "system:role:update", 2));
        seeds.add(new MenuSeed(23, 20, "menu.system.role.delete", button, null, null, null, "system:role:delete", 3));
        seeds.add(new MenuSeed(24, 20, "menu.system.role.grant", button, null, null, null, "system:role:grant", 4));
        seeds.add(new MenuSeed(30, 10, "menu.system.menu", menu, "/system/menu", "system/MenuList", "list", "system:menu:list", 93));
        seeds.add(new MenuSeed(31, 30, "menu.system.menu.create", button, null, null, null, "system:menu:create", 1));
        seeds.add(new MenuSeed(32, 30, "menu.system.menu.update", button, null, null, null, "system:menu:update", 2));
        seeds.add(new MenuSeed(33, 30, "menu.system.menu.delete", button, null, null, null, "system:menu:delete", 3));
        // 监控页操作点
        seeds.add(new MenuSeed(50, 6, "menu.monitor.python.reprobe", button, null, null, null, "monitor:python:reprobe", 1));
        // 技能页操作点
        seeds.add(new MenuSeed(59, 4, "menu.skills.list", button, null, null, null, "wb:skill:list", 0));
        seeds.add(new MenuSeed(60, 4, "menu.skills.create", button, null, null, null, "wb:skill:create", 1));
        seeds.add(new MenuSeed(61, 4, "menu.skills.update", button, null, null, null, "wb:skill:update", 2));
        seeds.add(new MenuSeed(62, 4, "menu.skills.delete", button, null, null, null, "wb:skill:delete", 3));
        // 文件页操作点
        seeds.add(new MenuSeed(70, 3, "menu.files.delete", button, null, null, null, "wb:file:delete", 1));
        // 知识库页操作点
        seeds.add(new MenuSeed(75, 45, "menu.kb.list", button, null, null, null, "wb:kb:list", 0));
        seeds.add(new MenuSeed(76, 45, "menu.kb.create", button, null, null, null, "wb:kb:create", 1));
        seeds.add(new MenuSeed(77, 45, "menu.kb.update", button, null, null, null, "wb:kb:update", 2));
        seeds.add(new MenuSeed(78, 45, "menu.kb.delete", button, null, null, null, "wb:kb:delete", 3));
        seeds.add(new MenuSeed(79, 45, "menu.kb.upload", button, null, null, null, "wb:kb:upload", 4));
        seeds.add(new MenuSeed(80, 45, "menu.kb.search", button, null, null, null, "wb:kb:search", 5));
        // 设置页：工具与供应方管理
        seeds.add(new MenuSeed(90, 8, "menu.settings.tool.list", button, null, null, null, "wb:tool:list", 1));
        seeds.add(new MenuSeed(91, 8, "menu.settings.tool.toggle", button, null, null, null, "wb:tool:toggle", 2));
        seeds.add(new MenuSeed(92, 8, "menu.settings.tool.invoke", button, null, null, null, "wb:tool:invoke", 3));
        seeds.add(new MenuSeed(93, 8, "menu.settings.provider.list", button, null, null, null, "wb:provider:list", 4));
        seeds.add(new MenuSeed(94, 8, "menu.settings.provider.create", button, null, null, null, "wb:provider:create", 5));
        seeds.add(new MenuSeed(95, 8, "menu.settings.provider.update", button, null, null, null, "wb:provider:update", 6));
        seeds.add(new MenuSeed(96, 8, "menu.settings.provider.delete", button, null, null, null, "wb:provider:delete", 7));
        // 创作台操作点
        seeds.add(new MenuSeed(120, 46, "menu.writer.list", button, null, null, null, "wb:doc:list", 0));
        seeds.add(new MenuSeed(121, 46, "menu.writer.create", button, null, null, null, "wb:doc:create", 1));
        seeds.add(new MenuSeed(122, 46, "menu.writer.update", button, null, null, null, "wb:doc:update", 2));
        seeds.add(new MenuSeed(123, 46, "menu.writer.delete", button, null, null, null, "wb:doc:delete", 3));
        seeds.add(new MenuSeed(124, 46, "menu.writer.search", button, null, null, null, "wb:doc:search", 4));
        // 提示词操作点
        seeds.add(new MenuSeed(130, 47, "menu.prompt.list", button, null, null, null, "wb:prompt:list", 0));
        seeds.add(new MenuSeed(131, 47, "menu.prompt.create", button, null, null, null, "wb:prompt:create", 1));
        seeds.add(new MenuSeed(132, 47, "menu.prompt.update", button, null, null, null, "wb:prompt:update", 2));
        seeds.add(new MenuSeed(133, 47, "menu.prompt.delete", button, null, null, null, "wb:prompt:delete", 3));
        // 工作流操作点
        seeds.add(new MenuSeed(140, 48, "menu.flow.list", button, null, null, null, "wb:flow:list", 0));
        seeds.add(new MenuSeed(141, 48, "menu.flow.create", button, null, null, null, "wb:flow:create", 1));
        seeds.add(new MenuSeed(142, 48, "menu.flow.update", button, null, null, null, "wb:flow:update", 2));
        seeds.add(new MenuSeed(143, 48, "menu.flow.delete", button, null, null, null, "wb:flow:delete", 3));
        seeds.add(new MenuSeed(144, 48, "menu.flow.run", button, null, null, null, "wb:flow:run", 4));
        return seeds;
    }
}
