package com.liteworkbench.api;

import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.dto.SettingREQ;
import com.liteworkbench.service.SettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 用户设置接口：登录即可用，仅本人 */
@RestController
@RequestMapping("/api/v1/setting")
@RequiredArgsConstructor
public class SettingController {

    private final SettingService settingService;

    /** 键值列表 */
    @GetMapping("/list")
    public R<Map<String, String>> list() {
        return R.ok(settingService.list(LoginContext.userId()));
    }

    /** 批量更新 */
    @Idempotent(prefix = "setting:update")
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid java.util.List<SettingREQ> reqs) {
        reqs.forEach(req -> settingService.update(req, LoginContext.userId()));
        return R.ok();
    }
}
