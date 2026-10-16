package com.liteworkbench.config;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.PasswordUtil;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.RoleCodeConst;
import com.liteworkbench.domain.constant.UserStateEnum;
import com.liteworkbench.domain.entity.SysRoleDO;
import com.liteworkbench.domain.entity.SysUserDO;
import com.liteworkbench.mapper.SysRoleMapper;
import com.liteworkbench.mapper.SysUserMapper;
import com.liteworkbench.service.SkillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** 启动期补种：种子账号（口令按环境变量对齐）与用户级内置技能副本；平台级种子见 resources/db/data.sql */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final String ADMIN_USERNAME = "admin";
    private static final String DEMO_USERNAME = "demo";
    private static final String SYSTEM = "system";

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SkillService skillService;
    private final AuthProperties authProperties;

    /** 启动时补齐账号与用户级技能 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        long start = System.currentTimeMillis();
        initSeedUsers();
        seedBuiltinSkills();
        log.info("[data.init] cost={}ms", System.currentTimeMillis() - start);
    }

    /** 种子账号：admin（超级管理员）、demo（普通用户）；已存在则只补回角色绑定，角色被误删重建后可自愈 */
    private void initSeedUsers() {
        SysRoleDO superRole = sysRoleMapper.selectByCode(RoleCodeConst.SUPER_ADMIN);
        SysRoleDO userRole = sysRoleMapper.selectByCode(RoleCodeConst.USER);
        if (Objects.isNull(superRole) || Objects.isNull(userRole)) {
            log.error("[data.init.user] 内置角色缺失，跳过种子账号补种：请检查 db/data.sql 是否执行成功");
            return;
        }
        // 口令只在实际建号时解析：否则每次启动都会重新生成并 WARN 打印一次，制造"口令又变了"的假象
        boolean adminExists = Objects.nonNull(sysUserMapper.selectByUsername(ADMIN_USERNAME));
        boolean demoExists = Objects.nonNull(sysUserMapper.selectByUsername(DEMO_USERNAME));
        String initPassword = adminExists && demoExists ? null : resolveInitPassword();

        if (adminExists) {
            ensureSeedUserRole(ADMIN_USERNAME, superRole.getRoleId());
        } else {
            createUser(ADMIN_USERNAME, "超级管理员", superRole.getRoleId(), initPassword);
        }
        if (demoExists) {
            ensureSeedUserRole(DEMO_USERNAME, userRole.getRoleId());
        } else {
            createUser(DEMO_USERNAME, "演示用户", userRole.getRoleId(), initPassword);
        }
        realignSeedPassword();
    }

    /** 种子账号的角色绑定：角色被删后重建会换新 ID，旧绑定指向已不存在的角色，这里补回 */
    private void ensureSeedUserRole(String username, String roleId) {
        SysUserDO user = sysUserMapper.selectByUsername(username);
        if (Objects.isNull(user)) {
            return;
        }
        List<String> owned = sysUserMapper.selectRoleIdsByUserId(user.getUserId());
        if (CollUtil.contains(owned, roleId)) {
            return;
        }
        sysUserMapper.insertUserRoles(user.getUserId(), List.of(roleId), SYSTEM);
        log.info("[data.init.user] 补回种子账号角色绑定 username={} roleId={}", username, roleId);
    }

    /** 初始密码：配置优先；未配置则随机生成并仅打印一次，绝不写死在代码或配置里 */
    private String resolveInitPassword() {
        String configured = authProperties.getAdminPassword();
        if (StrUtil.isNotBlank(configured)) {
            log.info("[data.init.user] 种子账号初始密码取自 LITEWORKBENCH_ADMIN_PASSWORD");
            return configured;
        }
        String generated = PasswordUtil.randomPassword();
        log.warn("[data.init.user] 未配置 LITEWORKBENCH_ADMIN_PASSWORD，已生成初始密码（仅本次打印，登录后必须立即改密）: {}",
                generated);
        return generated;
    }

    /** 未激活的种子账号按配置的初始密码对齐，账号完成改密（pwd_reset_required = false）后不再覆盖 */
    private void realignSeedPassword() {
        String configured = authProperties.getAdminPassword();
        if (StrUtil.isBlank(configured)) {
            return;
        }
        List.of(ADMIN_USERNAME, DEMO_USERNAME).forEach(username -> realignSeedPassword(username, configured));
    }

    /** 单个种子账号对齐：只动尚未激活的账号，已改密账号的口令不受配置项影响 */
    private void realignSeedPassword(String username, String rawPassword) {
        SysUserDO user = sysUserMapper.selectByUsername(username);
        if (Objects.isNull(user) || !Boolean.TRUE.equals(user.getPwdResetRequired())) {
            return;
        }
        String salt = PasswordUtil.randomSalt();
        sysUserMapper.updatePassword(user.getUserId(), PasswordUtil.hash(rawPassword, salt), salt,
                Boolean.TRUE, SYSTEM);
        log.info("[data.init.user] 种子账号尚未激活，已按 LITEWORKBENCH_ADMIN_PASSWORD 对齐密码 username={}",
                username);
    }

    /** 种子账号一律置强制改密标记，初始密码不长期在线 */
    private void createUser(String username, String nickname, String roleId, String rawPassword) {
        String salt = PasswordUtil.randomSalt();
        SysUserDO user = new SysUserDO();
        user.setUserId(IdGenerator.generate(IdPrefixConst.USER));
        user.setUsername(username);
        user.setPasswordSalt(salt);
        user.setPasswordHash(PasswordUtil.hash(rawPassword, salt));
        user.setNickname(nickname);
        user.setState(UserStateEnum.NORMAL.getCode());
        user.setPwdResetRequired(Boolean.TRUE);
        user.setCreatedBy(SYSTEM);
        user.setRemark("种子账号，首次登录后必须修改密码");
        sysUserMapper.insert(user);
        sysUserMapper.insertUserRoles(user.getUserId(), List.of(roleId), SYSTEM);
        log.info("[data.init.user] 已建种子账号 username={} userId={}", username, user.getUserId());
    }

    /** 存量用户补种内置技能副本：可降级数据，失败只记 error，不阻断启动 */
    private void seedBuiltinSkills() {
        try {
            for (String userId : sysUserMapper.selectAllUserIds()) {
                skillService.seedBuiltinForUser(userId);
            }
        } catch (Exception e) {
            log.error("[data.init.skill] 内置技能补种失败，已跳过 {}", e.getMessage());
        }
    }
}
