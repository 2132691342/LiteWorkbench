package com.liteworkbench.api;

import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.dto.MemoryREQ;
import com.liteworkbench.domain.vo.MemoryVO;
import com.liteworkbench.service.MemoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 长期记忆接口：用户私有资产，按归属用户隔离，登录即可用，不额外挂权限点 */
@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;

    /** 记忆分页，按权重与更新时间倒序 */
    @GetMapping("/page")
    public R<PageResult<MemoryVO>> page(@Valid MemoryREQ req) {
        return R.ok(memoryService.page(req));
    }

    /** 新增记忆，同 key 覆盖旧值 */
    @Idempotent(prefix = "memory", ttl = 5)
    @PostMapping("/create")
    public R<String> create(@RequestBody @Valid MemoryREQ req) {
        return R.ok(memoryService.create(req));
    }

    /** 修改记忆内容与维度 */
    @Idempotent(prefix = "memory", ttl = 5)
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid MemoryREQ req) {
        memoryService.update(req);
        return R.ok();
    }

    /** 删除一条长期记忆 */
    @Idempotent(prefix = "memory", ttl = 5)
    @PostMapping("/delete/{memoryId}")
    public R<Void> remove(@PathVariable String memoryId) {
        memoryService.remove(memoryId);
        return R.ok();
    }
}
