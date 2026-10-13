package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.constant.RunConst;
import com.liteworkbench.domain.dto.DocAssistREQ;
import com.liteworkbench.domain.dto.DocREQ;
import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.vo.DocVO;
import com.liteworkbench.domain.vo.DocVersionVO;
import com.liteworkbench.service.DocAssistService;
import com.liteworkbench.service.DocService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 创作文档接口：文档 CRUD、版本回溯、全文检索与 AI 辅助写作 */
@RestController
@RequestMapping("/api/v1/doc")
@RequiredArgsConstructor
public class DocController {

    private final DocService docService;
    private final DocAssistService docAssistService;

    /** 文档分页 */
    @SaCheckPermission(PermConst.WB_DOC_LIST)
    @GetMapping("/page")
    public R<PageResult<DocVO>> page(PageREQ req,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) String state) {
        return R.ok(docService.page(req, category, state));
    }

    /** 文档详情，含正文 */
    @SaCheckPermission(PermConst.WB_DOC_LIST)
    @GetMapping("/detail/{docId}")
    public R<DocVO> detail(@PathVariable String docId) {
        return R.ok(docService.detail(docId));
    }

    /** 新建文档 */
    @SaCheckPermission(PermConst.WB_DOC_CREATE)
    @Idempotent(prefix = "doc:create")
    @PostMapping("/create")
    public R<String> create(@RequestBody @Valid DocREQ req) {
        return R.ok(docService.create(req));
    }

    /** 保存正文，写版本并同步索引 */
    @SaCheckPermission(PermConst.WB_DOC_UPDATE)
    @Idempotent(prefix = "doc:update")
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid DocREQ req) {
        docService.update(req);
        return R.ok();
    }

    /** 删除文档、版本与索引 */
    @SaCheckPermission(PermConst.WB_DOC_DELETE)
    @Idempotent(prefix = "doc:delete")
    @PostMapping("/delete/{docId}")
    public R<Void> delete(@PathVariable String docId) {
        docService.delete(docId);
        return R.ok();
    }

    /** 全文检索，走 Manticore */
    @SaCheckPermission(PermConst.WB_DOC_SEARCH)
    @GetMapping("/search")
    public R<PageResult<DocVO>> search(PageREQ req,
                                       @RequestParam(required = false) String category) {
        return R.ok(docService.search(req, category));
    }

    /** 版本列表 */
    @SaCheckPermission(PermConst.WB_DOC_LIST)
    @GetMapping("/version/page")
    public R<PageResult<DocVersionVO>> versionPage(@RequestParam String docId,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size) {
        PageREQ req = new PageREQ();
        req.setPage((int) page);
        req.setSize((int) size);
        return R.ok(docService.versions(docId, req));
    }

    /** 版本快照详情 */
    @SaCheckPermission(PermConst.WB_DOC_LIST)
    @GetMapping("/version/detail/{versionId}")
    public R<DocVersionVO> versionDetail(@PathVariable String versionId) {
        return R.ok(docService.versionDetail(versionId));
    }

    /** 回滚到指定版本 */
    @SaCheckPermission(PermConst.WB_DOC_UPDATE)
    @Idempotent(prefix = "doc:restore")
    @PostMapping("/version/restore/{versionId}")
    public R<Void> restore(@PathVariable String versionId) {
        docService.restore(versionId);
        return R.ok();
    }

    /** AI 辅助写作，SSE 单通道 assist.event，流式返回；断连即取消模型调用 */
    @SaCheckPermission(PermConst.WB_DOC_UPDATE)
    @PostMapping(value = "/assist/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistStream(@RequestBody @Valid DocAssistREQ req) {
        SseEmitter emitter = new SseEmitter(RunConst.SSE_TIMEOUT_MS);
        docAssistService.assistStream(req, new SseDocAssistSink(emitter));
        return emitter;
    }
}
