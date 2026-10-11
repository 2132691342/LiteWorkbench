package com.liteworkbench.domain.constant;

/** 认证相关常量 */
public interface AuthConst {

    /** 请求头与令牌前缀 */
    String BEARER_PREFIX = "Bearer ";
    /** Sa-Token 会话中登录用户对象的键 */
    String SESSION_LOGIN_USER = "loginUser";
    /** 密码盐长度 */
    int SALT_LENGTH = 16;
    /** 密码最短长度 */
    int PASSWORD_MIN_LENGTH = 6;
    /** 密码最长长度 */
    int PASSWORD_MAX_LENGTH = 32;
}
