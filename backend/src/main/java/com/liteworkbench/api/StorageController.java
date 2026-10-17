package com.liteworkbench.api;

import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.vo.BucketAdminVO;
import com.liteworkbench.service.StorageAdminService;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 存储桶管理接口（管理员）：全平台桶清单、新建与删除 */
@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageAdminService storageAdminService;

    /** 全平台桶清单，含占用与保护标记 */
    @SaCheckPermission(PermConst.MONITOR_STORAGE_LIST)
    @GetMapping("/buckets")
    public R<List<BucketAdminVO>> buckets() {
        return R.ok(storageAdminService.listBuckets());
    }

    /** 新建桶 */
    @SaCheckPermission(PermConst.MONITOR_STORAGE_CREATE)
    @Idempotent(prefix = "storage:create")
    @PostMapping("/bucket/create")
    public R<Void> createBucket(@RequestBody Map<String, String> body) {
        storageAdminService.createBucket(body.get("bucketName"));
        return R.ok();
    }

    /** 删除桶：平台业务桶受保护，非空桶拒绝 */
    @SaCheckPermission(PermConst.MONITOR_STORAGE_DELETE)
    @Idempotent(prefix = "storage:delete")
    @PostMapping("/bucket/delete/{bucketName}")
    public R<Void> deleteBucket(@PathVariable String bucketName) {
        storageAdminService.deleteBucket(bucketName);
        return R.ok();
    }
}
