package com.liteworkbench.api;

import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.anno.RateLimit;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.dto.LoginREQ;
import com.liteworkbench.domain.dto.PasswordREQ;
import com.liteworkbench.domain.vo.LoginRESP;
import com.liteworkbench.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口：登录、登出、当前用户、修改密码 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 登录：登录名 + 密码，按 IP 限流防爆破 */
    @RateLimit(prefix = "auth:login", limit = 10, windowSeconds = 60, keyBy = RateLimit.KeyBy.IP)
    @PostMapping("/login")
    public R<LoginRESP> login(@RequestBody @Valid LoginREQ req) {
        return R.ok(authService.login(req));
    }

    /** 登出 */
    @Idempotent(prefix = "logout", ttl = 3)
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }

    /** 当前用户与权限、菜单树 */
    @GetMapping("/current")
    public R<LoginRESP> current() {
        return R.ok(authService.current());
    }

    /** 修改自己的密码 */
    @Idempotent(prefix = "auth:password")
    @PostMapping("/password")
    public R<Void> changePassword(@RequestBody @Valid PasswordREQ req) {
        authService.changePassword(req);
        return R.ok();
    }
}
