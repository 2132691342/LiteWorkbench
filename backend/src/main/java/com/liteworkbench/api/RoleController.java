package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.dto.RoleGrantREQ;
import com.liteworkbench.domain.dto.RoleREQ;
import com.liteworkbench.domain.vo.RoleVO;
import com.liteworkbench.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 角色管理接口 */
@RestController
@RequestMapping("/api/v1/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @SaCheckPermission(PermConst.SYSTEM_ROLE_LIST)
    @GetMapping("/page")
    public R<PageResult<RoleVO>> page(PageREQ req) {
        return R.ok(roleService.page(req));
    }

    /** 全量列表，下拉选择用 */
    @SaCheckPermission(PermConst.SYSTEM_ROLE_LIST)
    @GetMapping("/list")
    public R<List<RoleVO>> list() {
        return R.ok(roleService.list());
    }

    @SaCheckPermission(PermConst.SYSTEM_ROLE_LIST)
    @GetMapping("/detail/{roleId}")
    public R<RoleVO> detail(@PathVariable String roleId) {
        return R.ok(roleService.detail(roleId));
    }

    /** 角色已授权菜单 ID */
    @SaCheckPermission(PermConst.SYSTEM_ROLE_LIST)
    @GetMapping("/menu-ids/{roleId}")
    public R<List<String>> menuIds(@PathVariable String roleId) {
        return R.ok(roleService.menuIds(roleId));
    }

    @SaCheckPermission(PermConst.SYSTEM_ROLE_CREATE)
    @Idempotent(prefix = "role:create")
    @PostMapping("/create")
    public R<String> create(@RequestBody @Valid RoleREQ req) {
        return R.ok(roleService.create(req, LoginContext.userId()));
    }

    @SaCheckPermission(PermConst.SYSTEM_ROLE_UPDATE)
    @Idempotent(prefix = "role:update")
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid RoleREQ req) {
        roleService.update(req, LoginContext.userId());
        return R.ok();
    }

    /** 角色授权菜单，全量覆盖 */
    @SaCheckPermission(PermConst.SYSTEM_ROLE_GRANT)
    @Idempotent(prefix = "role:grant")
    @PostMapping("/grant")
    public R<Void> grant(@RequestBody @Valid RoleGrantREQ req) {
        roleService.grant(req, LoginContext.userId());
        return R.ok();
    }

    /** 逻辑删除 */
    @SaCheckPermission(PermConst.SYSTEM_ROLE_DELETE)
    @Idempotent(prefix = "role:delete")
    @PostMapping("/delete/{roleId}")
    public R<Void> delete(@PathVariable String roleId) {
        roleService.delete(roleId, LoginContext.userId());
        return R.ok();
    }
}
