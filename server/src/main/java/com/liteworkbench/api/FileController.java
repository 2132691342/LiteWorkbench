package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.vo.FileVO;
import com.liteworkbench.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 文件空间接口：上传走 multipart，下载走预签名直连 */
@RestController
@RequestMapping("/api/v1/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /** 分页列表 */
    @GetMapping("/page")
    public R<PageResult<FileVO>> page(@RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "20") long size,
                                      @RequestParam(required = false) String sessionId,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false) String keyword) {
        return R.ok(fileService.page(LoginContext.userId(), sessionId, category, keyword, page, size));
    }

    /** 上传，单文件 ≤ 100MB */
    @Idempotent(prefix = "file:upload")
    @PostMapping("/upload")
    public R<FileVO> upload(@RequestParam("file") MultipartFile file,
                            @RequestParam(required = false) String sessionId) {
        return R.ok(fileService.upload(file, LoginContext.userId(), sessionId));
    }

    /** 取预签名下载地址，15 分钟有效 */
    @GetMapping("/url/{fileId}")
    public R<String> url(@PathVariable String fileId) {
        return R.ok(fileService.presignedUrl(LoginContext.userId(), fileId));
    }

    /** 删除对象与元数据 */
    @SaCheckPermission(PermConst.WB_FILE_DELETE)
    @Idempotent(prefix = "file:delete")
    @PostMapping("/delete/{fileId}")
    public R<Void> delete(@PathVariable String fileId) {
        fileService.delete(LoginContext.userId(), fileId);
        return R.ok();
    }
}
