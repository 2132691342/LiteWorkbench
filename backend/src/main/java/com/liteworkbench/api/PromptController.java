package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.dto.PromptREQ;
import com.liteworkbench.domain.dto.PromptRenderREQ;
import com.liteworkbench.domain.vo.PromptVO;
import com.liteworkbench.service.PromptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提示词接口：模板 CRUD、内置只读、变量渲染 */
@RestController
@RequestMapping("/api/v1/prompt")
@RequiredArgsConstructor
public class PromptController {

    private final PromptService promptService;

    /** 分页，含内置模板 */
    @SaCheckPermission(PermConst.WB_PROMPT_LIST)
    @GetMapping("/page")
    public R<PageResult<PromptVO>> page(PageREQ req,
                                        @RequestParam(required = false) String category) {
        return R.ok(promptService.page(req, category));
    }

    @SaCheckPermission(PermConst.WB_PROMPT_LIST)
    @GetMapping("/detail/{promptId}")
    public R<PromptVO> detail(@PathVariable String promptId) {
        return R.ok(promptService.detail(promptId));
    }

    @SaCheckPermission(PermConst.WB_PROMPT_CREATE)
    @Idempotent(prefix = "prompt:create")
    @PostMapping("/create")
    public R<String> create(@RequestBody @Valid PromptREQ req) {
        return R.ok(promptService.create(req));
    }

    /** 更新，内置模板拒绝 */
    @SaCheckPermission(PermConst.WB_PROMPT_UPDATE)
    @Idempotent(prefix = "prompt:update")
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid PromptREQ req) {
        promptService.update(req);
        return R.ok();
    }

    /** 删除，内置模板拒绝 */
    @SaCheckPermission(PermConst.WB_PROMPT_DELETE)
    @Idempotent(prefix = "prompt:delete")
    @PostMapping("/delete/{promptId}")
    public R<Void> delete(@PathVariable String promptId) {
        promptService.delete(promptId);
        return R.ok();
    }

    /** 复制为自有模板 */
    @SaCheckPermission(PermConst.WB_PROMPT_CREATE)
    @Idempotent(prefix = "prompt:copy")
    @PostMapping("/copy/{promptId}")
    public R<String> copy(@PathVariable String promptId) {
        return R.ok(promptService.copy(promptId));
    }

    /** 变量渲染，返回渲染后文本 */
    @SaCheckPermission(PermConst.WB_PROMPT_LIST)
    @PostMapping("/render")
    public R<String> render(@RequestBody @Valid PromptRenderREQ req) {
        return R.ok(promptService.render(req));
    }
}
