package com.liteworkbench.domain.constant;

/** 认证相关常量 */
public interface AuthConst {

    /** Sa-Token 会话中登录用户对象的键 */
    String SESSION_LOGIN_USER = "loginUser";
    /** 密码盐长度 */
    int SALT_LENGTH = 16;
    /** 密码最短长度 */
    int PASSWORD_MIN_LENGTH = 8;
    /** 连续登录失败上限，达到即锁定 */
    int LOGIN_FAIL_LIMIT = 5;
    /** 登录锁定时长，秒 */
    long LOGIN_LOCK_SECONDS = 600L;
    /** 随机初始密码长度 */
    int GENERATED_PASSWORD_LENGTH = 16;
    /** 强制改密期间仍可访问的路径 */
    String[] PASSWORD_RESET_ALLOWED = {"/api/v1/auth/current", "/api/v1/auth/password",
            "/api/v1/auth/logout", "/api/v1/auth/public-key"};
}
