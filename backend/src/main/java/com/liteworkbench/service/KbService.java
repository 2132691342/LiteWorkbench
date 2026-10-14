package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.StateEnum;
import com.liteworkbench.domain.dto.KbREQ;
import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.entity.WbKbDO;
import com.liteworkbench.domain.entity.WbKbDocDO;
import com.liteworkbench.domain.vo.KbVO;
import com.liteworkbench.mapper.KbChunkIndexMapper;
import com.liteworkbench.mapper.WbKbDocMapper;
import com.liteworkbench.mapper.WbKbMapper;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 知识库：CRUD 与归属校验，删除级联清理文档原件与分块索引 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbService {

    private final WbKbMapper kbMapper;
    private final WbKbDocMapper kbDocMapper;
    private final KbChunkIndexMapper chunkIndexMapper;
    private final MinioClient minioClient;

    /** 分页查询，仅返回归属用户的知识库 */
    public PageResult<KbVO> page(PageREQ req, String userId) {
        long total = kbMapper.countPage(req, userId);
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<KbVO> list = kbMapper.selectPage(req, userId, req.offset(), req.pageSize())
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 详情，跨用户访问返回 404 */
    public KbVO detail(String kbId, String userId) {
        return toVO(getOwned(kbId, userId));
    }

    /** 新建知识库，用户内重名即拒绝 */
    public String create(KbREQ req, String userId) {
        requireUniqueName(userId, req.getKbName(), null);
        WbKbDO kb = new WbKbDO();
        kb.setKbId(IdGenerator.generate(IdPrefixConst.KB));
        kb.setUserId(userId);
        kb.setKbName(req.getKbName());
        kb.setDescription(req.getDescription());
        kb.setDocCount(0);
        kb.setChunkCount(0);
        kb.setState(StateEnum.NORMAL.getCode());
        kb.setCreatedBy(userId);
        kbMapper.insert(kb);
        log.info("[kb.create] userId={} kbId={} name={}", userId, kb.getKbId(), kb.getKbName());
        return kb.getKbId();
    }

    /** 更新名称与描述 */
    public void update(KbREQ req, String userId) {
        if (StrUtil.isBlank(req.getKbId())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "kbId 不能为空");
        }
        getOwned(req.getKbId(), userId);
        requireUniqueName(userId, req.getKbName(), req.getKbId());
        WbKbDO kb = new WbKbDO();
        kb.setKbId(req.getKbId());
        kb.setUserId(userId);
        kb.setKbName(req.getKbName());
        kb.setDescription(req.getDescription());
        kb.setUpdatedBy(userId);
        kbMapper.update(kb);
    }

    /** 删除：级联清分块索引、MinIO 原件与文档行 */
    public void delete(String kbId, String userId) {
        getOwned(kbId, userId);
        chunkIndexMapper.deleteByKb(kbId);
        List<WbKbDocDO> docs = kbDocMapper.selectListByKb(userId, kbId);
        docs.forEach(doc -> removeObjectQuietly(doc.getBucketName(), doc.getObjectKey()));
        docs.forEach(doc -> kbDocMapper.deleteById(userId, doc.getDocId(), userId));
        kbMapper.deleteById(userId, kbId, userId);
        log.info("[kb.delete] userId={} kbId={} docs={}", userId, kbId, docs.size());
    }

    /** 归属校验：先按 (ID, user) 查，查不到即 404 不泄露他人资源是否存在 */
    public WbKbDO getOwned(String kbId, String userId) {
        WbKbDO kb = kbMapper.selectByIdAndUser(userId, kbId);
        if (Objects.isNull(kb)) {
            throw new BizException(ErrorCodeEnum.KB_NOT_FOUND, kbId);
        }
        return kb;
    }

    /** 文档增删与解析完成后同步冗余计数 */
    public void syncDocCounts(String kbId) {
        kbMapper.updateCounts(kbId, kbDocMapper.countByKb(kbId), kbDocMapper.sumChunkCount(kbId));
    }

    private void requireUniqueName(String userId, String kbName, String excludeId) {
        if (kbMapper.countByName(userId, kbName, excludeId) > 0) {
            throw new BizException(ErrorCodeEnum.KB_NAME_CONFLICT, kbName);
        }
    }

    /** 原件删除失败仅告警：行与索引已清理，孤儿对象可按 user/{userId}/kbdoc 前缀清扫 */
    private void removeObjectQuietly(String bucketName, String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName).object(objectKey).build());
        } catch (Exception e) {
            log.warn("[kb.delete] 原件删除失败 objectKey={} {}", objectKey, e.getMessage());
        }
    }

    private KbVO toVO(WbKbDO kb) {
        KbVO vo = new KbVO();
        vo.setKbId(kb.getKbId());
        vo.setUserId(kb.getUserId());
        vo.setKbName(kb.getKbName());
        vo.setDescription(kb.getDescription());
        vo.setDocCount(kb.getDocCount());
        vo.setChunkCount(kb.getChunkCount());
        vo.setCreatedAt(TimeUtil.toMillis(kb.getCreatedAt()));
        vo.setUpdatedAt(TimeUtil.toMillis(kb.getUpdatedAt()));
        return vo;
    }
}
