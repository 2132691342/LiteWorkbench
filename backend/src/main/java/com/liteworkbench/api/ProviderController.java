package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.dto.ProviderREQ;
import com.liteworkbench.domain.vo.ProviderPresetVO;
import com.liteworkbench.domain.vo.ProviderVO;
import com.liteworkbench.service.ProviderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 模型供应方接口：平台级密钥配置与连通性测试 */
@RestController
@RequestMapping("/api/v1/provider")
@RequiredArgsConstructor
public class ProviderController {

    private final ProviderService providerService;

    /** 列表，密钥脱敏，权限与预设一致：供应方为平台级配置，仅管理角色可读 */
    @SaCheckPermission(PermConst.WB_PROVIDER_LIST)
    @GetMapping("/list")
    public R<List<ProviderVO>> list() {
        return R.ok(providerService.list());
    }

    /** 预设列表：code / baseUrl / defaultModel / 协议通道，新建表单下拉用 */
    @SaCheckPermission(PermConst.WB_PROVIDER_LIST)
    @GetMapping("/presets")
    public R<List<ProviderPresetVO>> presets() {
        return R.ok(providerService.presets());
    }

    /** 新建 */
    @SaCheckPermission(PermConst.WB_PROVIDER_CREATE)
    @Idempotent(prefix = "provider:create")
    @PostMapping("/create")
    public R<String> create(@RequestBody @Valid ProviderREQ req) {
        return R.ok(providerService.create(req, LoginContext.userId()));
    }

    /** 更新 */
    @SaCheckPermission(PermConst.WB_PROVIDER_UPDATE)
    @Idempotent(prefix = "provider:update")
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid ProviderREQ req) {
        providerService.update(req, LoginContext.userId());
        return R.ok();
    }

    /** 删除 */
    @SaCheckPermission(PermConst.WB_PROVIDER_DELETE)
    @Idempotent(prefix = "provider:delete")
    @PostMapping("/delete/{providerId}")
    public R<Void> delete(@PathVariable String providerId) {
        providerService.delete(providerId, LoginContext.userId());
        return R.ok();
    }

    /** 连通性测试 */
    @SaCheckPermission(PermConst.WB_PROVIDER_UPDATE)
    @Idempotent(prefix = "provider:test")
    @PostMapping("/test/{providerId}")
    public R<ProviderVO> test(@PathVariable String providerId) {
        return R.ok(providerService.test(providerId));
    }
}
