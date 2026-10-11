package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.dto.SkillREQ;
import com.liteworkbench.domain.vo.SkillVO;
import com.liteworkbench.service.SkillService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 技能接口：两段式技能管理，按用户归属 */
@RestController
@RequestMapping("/api/v1/skill")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    /** 分页查询 */
    @SaCheckPermission(PermConst.WB_SKILL_LIST)
    @GetMapping("/page")
    public R<PageResult<SkillVO>> page(PageREQ req) {
        return R.ok(skillService.page(req, LoginContext.userId()));
    }

    /** 新建 */
    @SaCheckPermission(PermConst.WB_SKILL_CREATE)
    @Idempotent(prefix = "skill:create")
    @PostMapping("/create")
    public R<String> create(@RequestBody @Valid SkillREQ req) {
        return R.ok(skillService.create(req, LoginContext.userId()));
    }

    /** 全量更新 */
    @SaCheckPermission(PermConst.WB_SKILL_UPDATE)
    @Idempotent(prefix = "skill:update")
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid SkillREQ req) {
        skillService.update(req, LoginContext.userId());
        return R.ok();
    }

    /** 删除 */
    @SaCheckPermission(PermConst.WB_SKILL_DELETE)
    @Idempotent(prefix = "skill:delete")
    @PostMapping("/delete/{skillId}")
    public R<Void> delete(@PathVariable String skillId) {
        skillService.delete(skillId, LoginContext.userId());
        return R.ok();
    }
}
