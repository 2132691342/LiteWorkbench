package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.dto.MenuREQ;
import com.liteworkbench.domain.vo.MenuVO;
import com.liteworkbench.service.MenuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 菜单与权限点管理接口 */
@RestController
@RequestMapping("/api/v1/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    /** 全量菜单树，管理页使用 */
    @SaCheckPermission(PermConst.SYSTEM_MENU_LIST)
    @GetMapping("/tree")
    public R<List<MenuVO>> tree() {
        return R.ok(menuService.tree());
    }

    /** 当前用户可见菜单树，前端动态路由使用 */
    @GetMapping("/user-tree")
    public R<List<MenuVO>> userTree() {
        return R.ok(menuService.userTree(LoginContext.userId()));
    }

    @SaCheckPermission(PermConst.SYSTEM_MENU_LIST)
    @GetMapping("/detail/{menuId}")
    public R<MenuVO> detail(@PathVariable String menuId) {
        return R.ok(menuService.detail(menuId));
    }

    @SaCheckPermission(PermConst.SYSTEM_MENU_CREATE)
    @Idempotent(prefix = "menu:create")
    @PostMapping("/create")
    public R<String> create(@RequestBody @Valid MenuREQ req) {
        return R.ok(menuService.create(req, LoginContext.userId()));
    }

    @SaCheckPermission(PermConst.SYSTEM_MENU_UPDATE)
    @Idempotent(prefix = "menu:update")
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid MenuREQ req) {
        menuService.update(req, LoginContext.userId());
        return R.ok();
    }

    @SaCheckPermission(PermConst.SYSTEM_MENU_DELETE)
    @Idempotent(prefix = "menu:delete")
    @PostMapping("/delete/{menuId}")
    public R<Void> delete(@PathVariable String menuId) {
        menuService.delete(menuId, LoginContext.userId());
        return R.ok();
    }
}
