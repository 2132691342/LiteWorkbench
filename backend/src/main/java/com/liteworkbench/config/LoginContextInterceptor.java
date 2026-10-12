package com.liteworkbench.config;

import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.LoginUser;
import com.liteworkbench.domain.constant.AuthConst;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

/** 请求进入时把登录用户放入线程上下文，并拦截未改密的种子账号 */
public class LoginContextInterceptor implements HandlerInterceptor {

    /** 惰性加载登录用户到 ThreadLocal；处于强制改密状态的账号只放行白名单路径 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        LoginUser user = LoginContext.get();
        if (Objects.nonNull(user) && Boolean.TRUE.equals(user.getPwdResetRequired())
                && !allowed(request.getRequestURI())) {
            throw new BizException(ErrorCodeEnum.PASSWORD_RESET_REQUIRED);
        }
        return true;
    }

    /** 清理线程上下文，防止线程复用串号 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        LoginContext.clear();
    }

    private boolean allowed(String uri) {
        for (String path : AuthConst.PASSWORD_RESET_ALLOWED) {
            if (uri.startsWith(path)) {
                return true;
            }
        }
        return false;
    }
}
