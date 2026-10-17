package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.config.StorageProperties;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.dto.WorkspaceREQ;
import com.liteworkbench.domain.entity.WbWorkspaceDO;
import com.liteworkbench.domain.vo.WorkspaceVO;
import com.liteworkbench.mapper.WbWorkspaceMapper;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** 工作区：用户级资产容器，与 MinIO 桶一一绑定；桶名按 ULID 派生，全局唯一 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    /** 单用户工作区数量上限 */
    private static final int MAX_PER_USER = 20;
    /** 桶名前缀：MinIO 桶名 3~63 字符，小写字母数字与连字符 */
    private static final String BUCKET_PREFIX = "lwws-";

    private final WbWorkspaceMapper workspaceMapper;
    private final MinioClient minioClient;
    private final StorageProperties storageProperties;

    /** 当前用户工作区列表，含每个桶的对象数与占用 */
    public List<WorkspaceVO> list(String userId) {
        List<WbWorkspaceDO> rows = workspaceMapper.selectByUser(userId);
        return rows.stream().map(row -> {
            WorkspaceVO vo = toVO(row);
            BucketStat stat = statBucket(row.getBucketName());
            vo.setObjectCount(stat.objectCount());
            vo.setTotalSize(stat.totalSize());
            return vo;
        }).toList();
    }

    /** 新建工作区并幂等建桶：桶名由 ULID 派生，不与任何用户输入拼接 */
    @Transactional(rollbackFor = Exception.class)
    public String create(WorkspaceREQ req, String userId) {
        if (workspaceMapper.countByNameExclude(userId, req.getWorkspaceName(), "") > 0) {
            throw new BizException(ErrorCodeEnum.WORKSPACE_NAME_CONFLICT);
        }
        if (workspaceMapper.countByUser(userId) >= MAX_PER_USER) {
            throw new BizException(ErrorCodeEnum.WORKSPACE_LIMIT_REACHED);
        }
        String workspaceId = IdGenerator.generate(IdPrefixConst.WORKSPACE);
        String bucket = bucketOf(workspaceId);
        boolean first = workspaceMapper.countByUser(userId) == 0;
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[ws.create] 桶已创建 userId={} bucket={}", userId, bucket);
            }
        } catch (Exception e) {
            log.error("[ws.create] 桶创建失败 userId={} bucket={} {}", userId, bucket, e.getMessage());
            throw new BizException(ErrorCodeEnum.BUCKET_CREATE_FAILED, e.getMessage());
        }
        WbWorkspaceDO row = new WbWorkspaceDO();
        row.setWorkspaceId(workspaceId);
        row.setUserId(userId);
        row.setWorkspaceName(req.getWorkspaceName());
        row.setBucketName(bucket);
        row.setDescription(req.getDescription());
        // 首个工作区即默认：上传与会话在未指定时回落到它
        row.setIsDefault(first);
        row.setCreatedBy(userId);
        workspaceMapper.insert(row);
        log.info("[ws.create] userId={} workspaceId={} default={}", userId, workspaceId, first);
        return workspaceId;
    }

    /** 更新工作区名称与描述 */
    public void update(WorkspaceREQ req, String userId) {
        if (StrUtil.isBlank(req.getWorkspaceId())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "workspaceId 不能为空");
        }
        require(req.getWorkspaceId(), userId);
        if (workspaceMapper.countByNameExclude(userId, req.getWorkspaceName(), req.getWorkspaceId()) > 0) {
            throw new BizException(ErrorCodeEnum.WORKSPACE_NAME_CONFLICT);
        }
        WbWorkspaceDO row = new WbWorkspaceDO();
        row.setWorkspaceId(req.getWorkspaceId());
        row.setWorkspaceName(req.getWorkspaceName());
        row.setDescription(req.getDescription());
        row.setUpdatedBy(userId);
        workspaceMapper.update(row);
    }

    /** 设默认：先摘存量默认再置位，两步同事务，避免出现两个默认 */
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(String workspaceId, String userId) {
        require(workspaceId, userId);
        workspaceMapper.clearDefault(userId, userId);
        workspaceMapper.markDefault(workspaceId, userId);
        log.info("[ws.default] userId={} workspaceId={}", userId, workspaceId);
    }

    /** 删除工作区：默认工作区不可删；删除后无默认则最早的工作区顶上，保证回落目标存在 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String workspaceId, String userId) {
        WbWorkspaceDO row = require(workspaceId, userId);
        if (Boolean.TRUE.equals(row.getIsDefault())) {
            throw new BizException(ErrorCodeEnum.WORKSPACE_DEFAULT_REQUIRED);
        }
        workspaceMapper.deleteById(workspaceId, userId);
        if (Objects.isNull(workspaceMapper.selectDefault(userId))) {
            List<WbWorkspaceDO> rest = workspaceMapper.selectByUser(userId);
            if (!rest.isEmpty()) {
                workspaceMapper.clearDefault(userId, userId);
                workspaceMapper.markDefault(rest.get(0).getWorkspaceId(), userId);
            }
        }
        log.info("[ws.delete] userId={} workspaceId={} bucket={}", userId, workspaceId, row.getBucketName());
    }

    /** 解析工作区：id 为空回落默认工作区；用户无任何工作区时懒建一个，保证链路不中断 */
    public WbWorkspaceDO resolve(String userId, String workspaceId) {
        if (StrUtil.isNotBlank(workspaceId)) {
            return require(workspaceId, userId);
        }
        WbWorkspaceDO def = workspaceMapper.selectDefault(userId);
        if (Objects.nonNull(def)) {
            return def;
        }
        create(new WorkspaceREQ().setWorkspaceName("默认工作区"), userId);
        def = workspaceMapper.selectDefault(userId);
        if (Objects.isNull(def)) {
            // 并发首建下 countByUser 已非零，create 不会建默认行，按最早行兜底
            List<WbWorkspaceDO> rows = workspaceMapper.selectByUser(userId);
            if (rows.isEmpty()) {
                throw new BizException(ErrorCodeEnum.WORKSPACE_NOT_FOUND);
            }
            def = rows.get(0);
        }
        return def;
    }

    /** 当前登录用户的工作区（LoginContext 线程内取值，禁止从请求体采信 userId） */
    public WbWorkspaceDO resolveCurrent(String workspaceId) {
        return resolve(LoginContext.userId(), workspaceId);
    }

    /** 上传目标桶：显式工作区优先，否则用户默认工作区 */
    public String bucketFor(String userId, String workspaceId) {
        return resolve(userId, workspaceId).getBucketName();
    }

    /** 全局单桶名：平台种子数据与知识库原件的归属桶，配置项 liteworkbench.storage.bucket-name */
    public String platformBucket() {
        return storageProperties.getBucketName();
    }

    private WbWorkspaceDO require(String workspaceId, String userId) {
        WbWorkspaceDO row = workspaceMapper.selectByIdAndUser(workspaceId, userId);
        if (Objects.isNull(row)) {
            throw new BizException(ErrorCodeEnum.WORKSPACE_NOT_FOUND, workspaceId);
        }
        return row;
    }

    /** 桶名派生：前缀 + ULID 去前缀段转小写，不与用户输入拼接，天然唯一 */
    private String bucketOf(String workspaceId) {
        int at = workspaceId.indexOf('_');
        String tail = at > 0 ? workspaceId.substring(at + 1) : workspaceId;
        return BUCKET_PREFIX + tail.toLowerCase();
    }

    private WorkspaceVO toVO(WbWorkspaceDO row) {
        WorkspaceVO vo = new WorkspaceVO();
        vo.setWorkspaceId(row.getWorkspaceId());
        vo.setWorkspaceName(row.getWorkspaceName());
        vo.setBucketName(row.getBucketName());
        vo.setDescription(row.getDescription());
        vo.setIsDefault(row.getIsDefault());
        return vo;
    }

    /** 桶统计：递归列举累加对象数与字节数，失败返回零值不炸列表 */
    public BucketStat statBucket(String bucket) {
        long count = 0;
        long size = 0;
        try {
            Iterable<Result<Item>> objects = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).recursive(true).build());
            for (Result<Item> result : objects) {
                Item item = result.get();
                count++;
                size += item.size();
            }
        } catch (Exception e) {
            log.warn("[ws.stat] 桶统计失败 bucket={} {}", bucket, e.getMessage());
        }
        return new BucketStat(bucket, count, size);
    }

    /** 桶占用统计 */
    public record BucketStat(String bucket, long objectCount, long totalSize) {
    }
}
