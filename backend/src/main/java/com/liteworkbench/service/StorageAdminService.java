package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.config.StorageProperties;
import com.liteworkbench.domain.vo.BucketAdminVO;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Bucket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 存储桶管理（管理员）：全平台桶清单、新建与删除；平台业务桶标记保护，禁删 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageAdminService {

    /** 桶名规则：3~63 字符，小写字母数字与连字符，不能以连字符开头或结尾 */
    private static final Pattern BUCKET_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$");
    /** 工作区桶前缀：与 WorkspaceService.BUCKET_PREFIX 对齐 */
    private static final String WORKSPACE_BUCKET_PREFIX = "lwws-";

    private final MinioClient minioClient;
    private final StorageProperties storageProperties;
    private final WorkspaceService workspaceService;

    /** 全平台桶清单，含占用与保护标记 */
    public List<BucketAdminVO> listBuckets() {
        List<BucketAdminVO> list = new ArrayList<>();
        try {
            for (Bucket bucket : minioClient.listBuckets()) {
                BucketAdminVO vo = new BucketAdminVO();
                vo.setBucketName(bucket.name());
                vo.setCreationDate(Objects.nonNull(bucket.creationDate())
                        ? bucket.creationDate().toInstant().toEpochMilli() : null);
                WorkspaceService.BucketStat stat = workspaceService.statBucket(bucket.name());
                vo.setObjectCount(stat.objectCount());
                vo.setTotalSize(stat.totalSize());
                vo.setProtectedBucket(isProtected(bucket.name()));
                list.add(vo);
            }
        } catch (Exception e) {
            log.error("[storage.list] 桶清单获取失败 {}", e.getMessage());
            throw new BizException(ErrorCodeEnum.BUCKET_OP_FAILED, e.getMessage());
        }
        return list;
    }

    /** 新建桶：名校验后幂等创建 */
    public void createBucket(String bucketName) {
        String name = StrUtil.blankToDefault(bucketName, "").trim();
        if (!BUCKET_NAME.matcher(name).matches()) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID,
                    "桶名须为 3~63 位小写字母、数字或连字符，不能以连字符开头结尾");
        }
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(name).build());
            if (exists) {
                throw new BizException(ErrorCodeEnum.STATE_CONFLICT, "桶已存在");
            }
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(name).build());
            log.info("[storage.create] 桶已创建 {}", name);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[storage.create] 桶创建失败 {} {}", name, e.getMessage());
            throw new BizException(ErrorCodeEnum.BUCKET_CREATE_FAILED, e.getMessage());
        }
    }

    /** 删除桶：平台业务桶禁删；非空桶 MinIO 直接拒绝，转成可读错误 */
    public void deleteBucket(String bucketName) {
        if (isProtected(bucketName)) {
            throw new BizException(ErrorCodeEnum.BUCKET_OP_FAILED, "平台业务桶受保护，不能删除");
        }
        try {
            minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucketName).build());
            log.info("[storage.delete] 桶已删除 {}", bucketName);
        } catch (ErrorResponseException e) {
            String code = StrUtil.nullToEmpty(e.errorResponse().code());
            if ("BucketNotEmpty".equals(code)) {
                throw new BizException(ErrorCodeEnum.BUCKET_NOT_EMPTY);
            }
            throw new BizException(ErrorCodeEnum.BUCKET_OP_FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("[storage.delete] 桶删除失败 {} {}", bucketName, e.getMessage());
            throw new BizException(ErrorCodeEnum.BUCKET_OP_FAILED, e.getMessage());
        }
    }

    /** 保护判定：平台主桶与工作区桶（lwws- 前缀）不可经管理端删除 */
    private boolean isProtected(String bucketName) {
        return Objects.equals(bucketName, storageProperties.getBucketName())
                || StrUtil.startWith(bucketName, WORKSPACE_BUCKET_PREFIX);
    }
}
