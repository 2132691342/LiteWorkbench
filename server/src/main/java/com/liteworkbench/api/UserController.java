package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.dto.ResetPasswordREQ;
import com.liteworkbench.domain.dto.UserPageREQ;
import com.liteworkbench.domain.dto.UserREQ;
import com.liteworkbench.domain.vo.UserVO;
import com.liteworkbench.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户管理接口 */
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 分页查询 */
    @SaCheckPermission(PermConst.SYSTEM_USER_LIST)
    @GetMapping("/page")
    public R<PageResult<UserVO>> page(UserPageREQ req) {
        return R.ok(userService.page(req));
    }

    /** 详情 */
    @SaCheckPermission(PermConst.SYSTEM_USER_LIST)
    @GetMapping("/detail/{userId}")
    public R<UserVO> detail(@PathVariable String userId) {
        return R.ok(userService.detail(userId));
    }

    /** 新建用户 */
    @SaCheckPermission(PermConst.SYSTEM_USER_CREATE)
    @Idempotent(prefix = "user:create")
    @PostMapping("/create")
    public R<String> create(@RequestBody @Valid UserREQ req) {
        return R.ok(userService.create(req, LoginContext.userId()));
    }

    /** 修改资料与角色 */
    @SaCheckPermission(PermConst.SYSTEM_USER_UPDATE)
    @Idempotent(prefix = "user:update")
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid UserREQ req) {
        userService.update(req, LoginContext.userId());
        return R.ok();
    }

    /** 启用 / 停用 */
    @SaCheckPermission(PermConst.SYSTEM_USER_UPDATE)
    @Idempotent(prefix = "user:toggle")
    @PostMapping("/toggle/{userId}")
    public R<Void> toggle(@PathVariable String userId) {
        userService.toggle(userId, LoginContext.userId());
        return R.ok();
    }

    /** 重置密码 */
    @SaCheckPermission(PermConst.SYSTEM_USER_RESET)
    @Idempotent(prefix = "user:reset")
    @PostMapping("/reset-password")
    public R<Void> resetPassword(@RequestBody @Valid ResetPasswordREQ req) {
        userService.resetPassword(req, LoginContext.userId());
        return R.ok();
    }

    /** 逻辑删除 */
    @SaCheckPermission(PermConst.SYSTEM_USER_DELETE)
    @Idempotent(prefix = "user:delete")
    @PostMapping("/delete/{userId}")
    public R<Void> delete(@PathVariable String userId) {
        userService.delete(userId, LoginContext.userId());
        return R.ok();
    }
}
