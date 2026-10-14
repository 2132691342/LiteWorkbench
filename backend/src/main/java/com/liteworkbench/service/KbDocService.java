package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.spi.KbSearchPort;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.config.KbProperties;
import com.liteworkbench.config.StorageProperties;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.KbConst;
import com.liteworkbench.domain.constant.KbDocStateEnum;
import com.liteworkbench.domain.dto.KbDocParseDTO;
import com.liteworkbench.domain.dto.UploadFileDTO;
import com.liteworkbench.domain.entity.WbKbDocDO;
import com.liteworkbench.domain.vo.KbChunkVO;
import com.liteworkbench.domain.vo.KbDocVO;
import com.liteworkbench.mapper.KbChunkIndexMapper;
import com.liteworkbench.mapper.WbKbDocMapper;
import com.liteworkbench.mq.producer.KbDocParseProducer;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 知识库文档：上传（MinIO + MQ 派发）、状态查询、重解析、删除与分块检索；实现 Agent 检索端口 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbDocService implements KbSearchPort {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WbKbDocMapper kbDocMapper;
    private final KbChunkIndexMapper chunkIndexMapper;
    private final KbService kbService;
    private final DocTextExtractor textExtractor;
    private final KbDocParseProducer parseProducer;
    private final MinioClient minioClient;
    private final StorageProperties storageProperties;
    private final KbProperties kbProperties;

    /** 上传：原件进 MinIO，行置 pending，解析经 MQ 异步执行 */
    public KbDocVO upload(String kbId, UploadFileDTO file, String userId) {
        kbService.getOwned(kbId, userId);
        validate(file);
        String docId = IdGenerator.generate(IdPrefixConst.KB_DOC);
        String fileName = sanitizeName(file.fileName());
        String objectKey = StrUtil.format("user/{}/{}/{}/{}/{}",
                userId, "kbdoc", LocalDate.now().format(DAY_FORMAT), docId, fileName);
        try (InputStream in = file.content()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(storageProperties.getBucketName())
                    .object(objectKey)
                    .contentType(StrUtil.blankToDefault(file.contentType(), "application/octet-stream"))
                    .stream(in, file.fileSize(), -1)
                    .build());
        } catch (Exception e) {
            log.error("[kb.doc.upload] 原件写入失败 userId={} doc={} {}", userId, fileName, e.getMessage());
            throw new BizException(ErrorCodeEnum.FILE_UPLOAD_FAILED, e.getMessage());
        }

        WbKbDocDO doc = new WbKbDocDO();
        doc.setDocId(docId);
        doc.setUserId(userId);
        doc.setKbId(kbId);
        doc.setDocName(fileName);
        doc.setObjectKey(objectKey);
        doc.setBucketName(storageProperties.getBucketName());
        doc.setFileSize(file.fileSize());
        doc.setContentType(file.contentType());
        doc.setState(KbDocStateEnum.PENDING.getCode());
        doc.setCreatedBy(userId);
        try {
            kbDocMapper.insert(doc);
        } catch (Exception e) {
            removeObjectQuietly(objectKey);
            throw new BizException(ErrorCodeEnum.FILE_UPLOAD_FAILED, e.getMessage());
        }
        try {
            parseProducer.publish(new KbDocParseDTO(docId, userId, kbId,
                    doc.getBucketName(), objectKey, fileName));
        } catch (Exception e) {
            // 投递失败必须落 failed：留在 pending 会被 reparse 的 STATE_CONFLICT 挡住，只能删了重传
            markFailed(docId, userId, "解析任务投递失败：" + e.getMessage());
            doc.setState(KbDocStateEnum.FAILED.getCode());
            log.warn("[kb.doc.upload] 解析任务投递失败 docId={} {}", docId, e.getMessage());
        }
        kbService.syncDocCounts(kbId);
        log.info("[kb.doc.upload] userId={} kbId={} docId={} size={}", userId, kbId, docId, file.fileSize());
        return toVO(doc);
    }

    public PageResult<KbDocVO> page(String kbId, String userId, String keyword, String state,
                                    long page, long size) {
        kbService.getOwned(kbId, userId);
        long pageNo = Math.max(page, 1L);
        long pageSize = Math.min(Math.max(size, 1L), 200L);
        long offset = (pageNo - 1) * pageSize;
        long total = kbDocMapper.countPage(kbId, userId, keyword, state);
        if (total <= 0) {
            return PageResult.empty(pageNo, pageSize);
        }
        List<KbDocVO> list = kbDocMapper.selectPage(kbId, userId, keyword, state, offset, pageSize)
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, pageNo, pageSize);
    }

    /** 重解析：failed / ready 均可重放，重放后旧分块在解析成功时被替换 */
    public void reparse(String docId, String userId) {
        WbKbDocDO doc = getOwned(docId, userId);
        if (KbDocStateEnum.PROCESSING.getCode().equals(doc.getState())
                || KbDocStateEnum.PENDING.getCode().equals(doc.getState())) {
            throw new BizException(ErrorCodeEnum.STATE_CONFLICT, doc.getState());
        }
        kbDocMapper.updateState(docId, userId, KbDocStateEnum.PENDING.getCode(), null, userId);
        try {
            parseProducer.publish(new KbDocParseDTO(docId, userId, doc.getKbId(),
                    doc.getBucketName(), doc.getObjectKey(), doc.getDocName()));
        } catch (Exception e) {
            // 与上传同处理：投递不通就落 failed，让用户能再点一次而不是卡在 pending
            markFailed(docId, userId, "解析任务投递失败：" + e.getMessage());
            log.warn("[kb.doc.reparse] 解析任务投递失败 docId={} {}", docId, e.getMessage());
            return;
        }
        log.info("[kb.doc.reparse] userId={} docId={}", userId, docId);
    }

    /** 删除：清分块索引、MinIO 原件与文档行，并同步知识库计数 */
    public void delete(String docId, String userId) {
        WbKbDocDO doc = getOwned(docId, userId);
        chunkIndexMapper.deleteByDoc(docId);
        removeObjectQuietly(doc.getObjectKey());
        kbDocMapper.deleteById(userId, docId, userId);
        kbService.syncDocCounts(doc.getKbId());
        log.info("[kb.doc.delete] userId={} docId={}", userId, docId);
    }

    /** 分块检索：kbId 可选，跨库时只限定用户；高亮由前端渲染 */
    public PageResult<KbChunkVO> search(String userId, String kbId, String keyword, long page, long size) {
        if (StrUtil.isBlank(keyword)) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "keyword 不能为空");
        }
        if (StrUtil.isNotBlank(kbId)) {
            kbService.getOwned(kbId, userId);
        }
        long pageNo = Math.max(page, 1L);
        long pageSize = Math.min(Math.max(size, 1L), 50L);
        long offset = (pageNo - 1) * pageSize;
        long total = chunkIndexMapper.count(userId, kbId, keyword);
        if (total <= 0) {
            return PageResult.empty(pageNo, pageSize);
        }
        List<KbChunkVO> list = chunkIndexMapper.search(userId, kbId, keyword, offset, pageSize);
        return PageResult.of(list, total, pageNo, pageSize);
    }

    /** 解析文档：提文本 → 分块 → 换索引 → 更新状态与计数；异常置 failed 不外抛 */
    public void parse(KbDocParseDTO task) {
        WbKbDocDO doc = kbDocMapper.selectByIdAndUser(task.userId(), task.docId());
        if (Objects.isNull(doc) || KbDocStateEnum.READY.getCode().equals(doc.getState())) {
            return;
        }
        long start = System.currentTimeMillis();
        kbDocMapper.updateState(task.docId(), task.userId(),
                KbDocStateEnum.PROCESSING.getCode(), null, task.userId());
        try {
            String content = textExtractor.extract(task.bucketName(), task.objectKey(), task.docName());
            List<String> chunks = KbTextChunker.chunk(content,
                    kbProperties.getChunk().getTargetSize(), kbProperties.getChunk().getOverlap());
            chunkIndexMapper.deleteByDoc(task.docId());
            List<KbChunkVO> docs = buildChunkDocs(task, chunks);
            chunkIndexMapper.addBatch(docs);
            kbDocMapper.updateCounts(task.docId(), content.length(), docs.size());
            kbDocMapper.updateState(task.docId(), task.userId(),
                    KbDocStateEnum.READY.getCode(), null, task.userId());
            kbService.syncDocCounts(task.kbId());
            log.info("[kb.parse] docId={} chunks={} chars={} cost={}ms",
                    task.docId(), docs.size(), content.length(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[kb.parse] 解析失败 docId={} {}", task.docId(), e.getMessage());
            markFailed(task.docId(), task.userId(), e.getMessage());
        }
    }

    /** Agent 检索端口：拼装带来源标注的片段，供模型引用 */
    @Override
    public String search(String userId, String keyword, String kbId, int limit) {
        int capped = Math.min(Math.max(limit, 1), KbConst.TOOL_SEARCH_LIMIT);
        List<KbChunkVO> chunks = chunkIndexMapper.search(userId, kbId, keyword, 0, capped);
        if (chunks.isEmpty()) {
            return "未检索到相关内容，请更换关键词";
        }
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            KbChunkVO chunk = chunks.get(i);
            output.append('[').append(i + 1).append("] ")
                    .append(StrUtil.nullToDefault(chunk.getDocName(), "未知文档"))
                    .append(" 第").append(chunk.getSeq()).append("块\n")
                    .append(StrUtil.nullToEmpty(chunk.getContent())).append('\n');
            if (output.length() >= KbConst.TOOL_OUTPUT_MAX) {
                break;
            }
        }
        return StrUtil.maxLength(output.toString(), KbConst.TOOL_OUTPUT_MAX);
    }

    /** 校验文件大小与扩展名白名单 */
    private void validate(UploadFileDTO file) {
        if (Objects.isNull(file) || Objects.isNull(file.content()) || file.fileSize() <= 0) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "文件不能为空");
        }
        if (file.fileSize() > KbConst.MAX_DOC_SIZE) {
            throw new BizException(ErrorCodeEnum.FILE_TOO_LARGE, String.valueOf(KbConst.MAX_DOC_SIZE));
        }
        String extension = StrUtil.subAfter(StrUtil.nullToDefault(file.fileName(), ""), '.', true)
                .toLowerCase();
        if (!KbConst.TEXT_EXTENSIONS.contains(extension)
                && !KbConst.PARSER_EXTENSIONS.contains(extension)) {
            throw new BizException(ErrorCodeEnum.KB_DOC_TYPE_UNSUPPORTED, extension);
        }
    }

    private List<KbChunkVO> buildChunkDocs(KbDocParseDTO task, List<String> chunks) {
        List<KbChunkVO> docs = new ArrayList<>(chunks.size());
        long ts = System.currentTimeMillis();
        for (int i = 0; i < chunks.size(); i++) {
            KbChunkVO vo = new KbChunkVO();
            vo.setChunkId(IdGenerator.generate(IdPrefixConst.KB_CHUNK));
            vo.setUserId(task.userId());
            vo.setKbId(task.kbId());
            vo.setDocId(task.docId());
            vo.setDocName(task.docName());
            vo.setSeq(i + 1);
            vo.setContent(chunks.get(i));
            vo.setCreatedAt(ts);
            docs.add(vo);
        }
        return docs;
    }

    private WbKbDocDO getOwned(String docId, String userId) {
        WbKbDocDO doc = kbDocMapper.selectByIdAndUser(userId, docId);
        if (Objects.isNull(doc)) {
            throw new BizException(ErrorCodeEnum.KB_DOC_NOT_FOUND, docId);
        }
        return doc;
    }

    /** 文件名去路径与控制字符，防对象键注入 */
    private String sanitizeName(String name) {
        String clean = StrUtil.blankToDefault(name, "doc");
        clean = clean.replaceAll("[\\\\/]", "_").replaceAll("[\\x00-\\x1F]", "");
        return StrUtil.maxLength(clean, 200);
    }

    /** 置文档失败态并截断原因，reason 列宽 500 */
    private void markFailed(String docId, String userId, String reason) {
        kbDocMapper.updateState(docId, userId, KbDocStateEnum.FAILED.getCode(),
                StrUtil.maxLength(reason, 500), userId);
    }

    /** 对象删除失败仅告警 */
    private void removeObjectQuietly(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(storageProperties.getBucketName()).object(objectKey).build());
        } catch (Exception e) {
            log.warn("[kb.doc] 原件删除失败 objectKey={} {}", objectKey, e.getMessage());
        }
    }

    private KbDocVO toVO(WbKbDocDO doc) {
        KbDocVO vo = new KbDocVO();
        vo.setDocId(doc.getDocId());
        vo.setKbId(doc.getKbId());
        vo.setDocName(doc.getDocName());
        vo.setFileSize(doc.getFileSize());
        vo.setContentType(doc.getContentType());
        vo.setCharCount(doc.getCharCount());
        vo.setChunkCount(doc.getChunkCount());
        vo.setState(doc.getState());
        vo.setErrorMessage(doc.getErrorMessage());
        vo.setCreatedAt(TimeUtil.toMillis(doc.getCreatedAt()));
        vo.setUpdatedAt(TimeUtil.toMillis(doc.getUpdatedAt()));
        return vo;
    }
}
