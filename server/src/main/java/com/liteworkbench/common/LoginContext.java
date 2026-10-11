package com.liteworkbench.common;

import cn.dev33.satoken.stp.StpUtil;
import com.liteworkbench.domain.constant.AuthConst;

import java.util.Objects;

/** 请求线程的登录上下文：用户身份的唯一起点 */
public final class LoginContext {

    private LoginContext() {
    }

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    /** 请求结束时清理 */
    public static void clear() {
        HOLDER.remove();
    }

    /** 当前登录用户，未登录返回 null */
    public static LoginUser get() {
        LoginUser user = HOLDER.get();
        if (Objects.nonNull(user)) {
            return user;
        }
        try {
            Object value = StpUtil.getSession().get(AuthConst.SESSION_LOGIN_USER);
            if (value instanceof LoginUser loginUser) {
                HOLDER.set(loginUser);
                return loginUser;
            }
        } catch (Exception ignored) {
            // 未登录或非 Web 线程，返回 null
        }
        return null;
    }

    /** 当前用户 ID，未登录抛 UNAUTHORIZED */
    public static String userId() {
        return require().getUserId();
    }

    private static LoginUser require() {
        LoginUser user = get();
        if (Objects.isNull(user)) {
            throw new BizException(ErrorCodeEnum.UNAUTHORIZED);
        }
        return user;
    }
}
