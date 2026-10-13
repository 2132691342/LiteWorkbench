package com.liteworkbench.config;

import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.LoginUser;
import com.liteworkbench.domain.constant.AppConst;
import com.liteworkbench.domain.constant.AuthConst;
import com.liteworkbench.domain.constant.UserStateEnum;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

/** 请求进入时把登录用户放入线程上下文与 MDC，并拦截未改密的种子账号 */
public class LoginContextInterceptor implements HandlerInterceptor {

    /** 惰性加载登录用户到 ThreadLocal；已登录同时把 userId 注入 MDC；停用账号与未改密种子账号在此拦截 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        LoginUser user = LoginContext.get();
        if (Objects.nonNull(user)) {
            MDC.put(AppConst.MDC_USER_ID, user.getUserId());
            // 账号停用：快照状态非 normal 即拒绝，管理端停用后无需等待令牌过期
            if (!UserStateEnum.NORMAL.getCode().equals(user.getState())) {
                clearContext();
                throw new BizException(ErrorCodeEnum.ACCOUNT_DISABLED);
            }
            if (Boolean.TRUE.equals(user.getPwdResetRequired()) && !allowed(request.getRequestURI())) {
                // preHandle 抛异常时 Spring 不调用本拦截器的 afterCompletion，先自行清理线程上下文再抛，防线程复用串号
                clearContext();
                throw new BizException(ErrorCodeEnum.PASSWORD_RESET_REQUIRED);
            }
        }
        return true;
    }

    /** 清理线程上下文与 MDC：抛异常路径上 afterCompletion 不会执行，必须自行收尾 */
    private void clearContext() {
        LoginContext.clear();
        MDC.remove(AppConst.MDC_USER_ID);
        MDC.remove(AppConst.MDC_SESSION_ID);
        MDC.remove(AppConst.MDC_RUN_ID);
    }

    /** 清理线程上下文与 MDC，防止线程复用串号 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        LoginContext.clear();
        MDC.remove(AppConst.MDC_USER_ID);
        MDC.remove(AppConst.MDC_SESSION_ID);
        MDC.remove(AppConst.MDC_RUN_ID);
    }

    /** 强制改密白名单：仅放行取公钥、当前用户、改密与登出，前缀相等匹配防误放行 */
    private boolean allowed(String uri) {
        for (String path : AuthConst.PASSWORD_RESET_ALLOWED) {
            if (uri.equals(path)) {
                return true;
            }
        }
        return false;
    }
}
