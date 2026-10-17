package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.dto.KbREQ;
import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.dto.UploadFileDTO;
import com.liteworkbench.domain.vo.KbChunkVO;
import com.liteworkbench.domain.vo.KbDocVO;
import com.liteworkbench.domain.vo.KbVO;
import com.liteworkbench.service.KbDocService;
import com.liteworkbench.service.KbService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/** 知识库接口：库管理、文档上传解析、分块检索，资源按用户归属 */
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KbController {

    private final KbService kbService;
    private final KbDocService kbDocService;

    /** 知识库分页列表 */
    @SaCheckPermission(PermConst.WB_KB_LIST)
    @GetMapping("/page")
    public R<PageResult<KbVO>> page(PageREQ req) {
        return R.ok(kbService.page(req, LoginContext.userId()));
    }

    /** 知识库详情 */
    @SaCheckPermission(PermConst.WB_KB_LIST)
    @GetMapping("/detail/{kbId}")
    public R<KbVO> detail(@PathVariable String kbId) {
        return R.ok(kbService.detail(kbId, LoginContext.userId()));
    }

    /** 新建知识库 */
    @SaCheckPermission(PermConst.WB_KB_CREATE)
    @Idempotent(prefix = "kb:create")
    @PostMapping("/create")
    public R<String> create(@RequestBody @Valid KbREQ req) {
        return R.ok(kbService.create(req, LoginContext.userId()));
    }

    /** 修改知识库名称与说明 */
    @SaCheckPermission(PermConst.WB_KB_UPDATE)
    @Idempotent(prefix = "kb:update")
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid KbREQ req) {
        kbService.update(req, LoginContext.userId());
        return R.ok();
    }

    /** 删除知识库，级联清文档与分块 */
    @SaCheckPermission(PermConst.WB_KB_DELETE)
    @Idempotent(prefix = "kb:delete")
    @PostMapping("/delete/{kbId}")
    public R<Void> delete(@PathVariable String kbId) {
        kbService.delete(kbId, LoginContext.userId());
        return R.ok();
    }

    /** 库内文档分页 */
    @SaCheckPermission(PermConst.WB_KB_LIST)
    @GetMapping("/doc/page")
    public R<PageResult<KbDocVO>> docPage(@RequestParam String kbId,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String state) {
        return R.ok(kbDocService.page(kbId, LoginContext.userId(), keyword, state, page, size));
    }

    /** 上传文档，解析经 MQ 异步执行 */
    @SaCheckPermission(PermConst.WB_KB_UPLOAD)
    @Idempotent(prefix = "kb:upload")
    @PostMapping("/doc/upload/{kbId}")
    public R<KbDocVO> upload(@PathVariable String kbId,
                             @RequestParam("file") MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            UploadFileDTO payload = new UploadFileDTO(
                    file.getOriginalFilename(), file.getContentType(), file.getSize(), in);
            return R.ok(kbDocService.upload(kbId, payload, LoginContext.userId()));
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.FILE_UPLOAD_FAILED, e.getMessage());
        }
    }

    /** 删除文档并清理其分块 */
    @SaCheckPermission(PermConst.WB_KB_DELETE)
    @Idempotent(prefix = "kb:doc:delete")
    @PostMapping("/doc/delete/{docId}")
    public R<Void> deleteDoc(@PathVariable String docId) {
        kbDocService.delete(docId, LoginContext.userId());
        return R.ok();
    }

    /** 重解析失败文档 */
    @SaCheckPermission(PermConst.WB_KB_UPLOAD)
    @Idempotent(prefix = "kb:reparse")
    @PostMapping("/doc/reparse/{docId}")
    public R<Void> reparse(@PathVariable String docId) {
        kbDocService.reparse(docId, LoginContext.userId());
        return R.ok();
    }

    /** 分块检索，按相关度排序 */
    @SaCheckPermission(PermConst.WB_KB_SEARCH)
    @GetMapping("/search")
    public R<PageResult<KbChunkVO>> search(@RequestParam String keyword,
                                           @RequestParam(required = false) String kbId,
                                           @RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "10") long size) {
        return R.ok(kbDocService.search(LoginContext.userId(), kbId, keyword, page, size));
    }
}
