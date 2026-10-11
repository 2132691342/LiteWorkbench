package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.domain.constant.DocConst;
import com.liteworkbench.domain.constant.DocStateEnum;
import com.liteworkbench.domain.constant.DocTypeEnum;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.dto.DocREQ;
import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.entity.WbDocDO;
import com.liteworkbench.domain.entity.WbDocVersionDO;
import com.liteworkbench.domain.vo.DocVO;
import com.liteworkbench.domain.vo.DocVersionVO;
import com.liteworkbench.mapper.DocIndexMapper;
import com.liteworkbench.mapper.WbDocMapper;
import com.liteworkbench.mapper.WbDocVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 创作文档：CRUD、版本快照、全文索引同步与版本回滚 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocService {

    /** 富文本里需要整块剔除的片段 */
    private static final Pattern BLOCK_PATTERN =
            Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    /** 所有标签 */
    private static final Pattern TAG_PATTERN = Pattern.compile("(?s)<[^>]+>");
    /** 常见实体，转义后影响可读性 */
    private static final String[] ENTITY_SOURCES = {"&nbsp;", "&amp;", "&lt;", "&gt;", "&quot;", "&#39;"};
    private static final String[] ENTITY_TARGETS = {" ", "&", "<", ">", "\"", "'"};

    private final WbDocMapper docMapper;
    private final WbDocVersionMapper versionMapper;
    private final DocIndexMapper docIndexMapper;

    /** 分页查询，仅返回归属用户的文档 */
    public PageResult<DocVO> page(PageREQ req, String category, String state) {
        String userId = LoginContext.userId();
        long total = docMapper.countPage(req, userId, category, state);
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<DocVO> list = docMapper.selectPage(req, userId, category, state, req.offset(), req.pageSize())
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 详情，含正文 */
    public DocVO detail(String docId) {
        String userId = LoginContext.userId();
        return toVO(getOwned(docId, userId), true);
    }

    /** 新建文档：正文为空也给一条初始版本，保证版本链完整 */
    @Transactional(rollbackFor = Exception.class)
    public String create(DocREQ req) {
        String userId = LoginContext.userId();
        requireUniqueTitle(userId, req.getDocTitle(), null);
        String content = StrUtil.nullToEmpty(req.getContent());
        requireLength(content);
        WbDocDO doc = new WbDocDO()
                .setDocId(IdGenerator.generate(IdPrefixConst.DOC))
                .setUserId(userId)
                .setDocTitle(req.getDocTitle())
                .setDocType(resolveType(req.getDocType()))
                .setCategory(req.getCategory())
                .setTags(req.getTags())
                .setSummary(req.getSummary())
                .setContent(content)
                .setContentText(toPlainText(content, resolveType(req.getDocType())))
                .setWordCount(wordCount(content))
                .setVersionNo(1)
                .setSourceKbId(req.getSourceKbId())
                .setState(resolveState(req.getState()))
                .setCreatedBy(userId);
        docMapper.insert(doc);
        insertVersion(doc, 1, StrUtil.blankToDefault(req.getChangeNote(), "创建文档"));
        syncIndex(doc);
        log.info("[doc.create] userId={} docId={} title={}", userId, doc.getDocId(), doc.getDocTitle());
        return doc.getDocId();
    }

    /** 保存正文：版本号递增 + 写版本快照 + 同步索引，索引失败不回滚 */
    @Transactional(rollbackFor = Exception.class)
    public void update(DocREQ req) {
        String userId = LoginContext.userId();
        if (StrUtil.isBlank(req.getDocId())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "docId 不能为空");
        }
        WbDocDO existed = getOwned(req.getDocId(), userId);
        String content = StrUtil.nullToEmpty(req.getContent());
        requireLength(content);
        requireUniqueTitle(userId, req.getDocTitle(), req.getDocId());

        String docType = StrUtil.isNotBlank(req.getDocType()) ? resolveType(req.getDocType()) : existed.getDocType();
        int versionNo = Objects.requireNonNullElse(existed.getVersionNo(), 0) + 1;
        WbDocDO doc = new WbDocDO()
                .setDocId(existed.getDocId())
                .setUserId(userId)
                .setDocTitle(req.getDocTitle())
                .setDocType(docType)
                .setCategory(req.getCategory())
                .setTags(req.getTags())
                .setSummary(req.getSummary())
                .setContent(content)
                .setContentText(toPlainText(content, docType))
                .setWordCount(wordCount(content))
                .setVersionNo(versionNo)
                .setSourceKbId(req.getSourceKbId())
                .setState(resolveState(req.getState()))
                .setUpdatedBy(userId);
        docMapper.update(doc);
        insertVersion(doc, versionNo, StrUtil.blankToDefault(req.getChangeNote(), "保存正文"));
        versionMapper.trimKeep(existed.getDocId(), DocConst.VERSION_KEEP);
        syncIndex(doc);
        log.info("[doc.update] userId={} docId={} version={} words={}",
                userId, doc.getDocId(), versionNo, doc.getWordCount());
    }

    /** 删除文档：删版本行与索引文档 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        String userId = LoginContext.userId();
        getOwned(docId, userId);
        versionMapper.deleteByDoc(docId, userId);
        docMapper.deleteById(userId, docId, userId);
        docIndexMapper.delete(docId);
        log.info("[doc.delete] userId={} docId={}", userId, docId);
    }

    /** 全文检索，走 Redisearch 索引 */
    public PageResult<DocVO> search(PageREQ req, String category) {
        String userId = LoginContext.userId();
        long total = docIndexMapper.count(userId, category, req.getKeyword());
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<DocVO> list = docIndexMapper.search(userId, category, req.getKeyword(), req.offset(), req.pageSize());
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 版本列表，倒序 */
    public PageResult<DocVersionVO> versions(String docId, PageREQ req) {
        String userId = LoginContext.userId();
        getOwned(docId, userId);
        long total = versionMapper.countByDoc(docId, userId);
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<DocVersionVO> list = versionMapper.selectPage(docId, userId, req.offset(), req.pageSize())
                .stream().map(this::toVersionVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 版本快照详情，含正文 */
    public DocVersionVO versionDetail(String versionId) {
        String userId = LoginContext.userId();
        WbDocVersionDO version = versionMapper.selectByIdAndUser(userId, versionId);
        if (Objects.isNull(version)) {
            throw new BizException(ErrorCodeEnum.DOC_VERSION_NOT_FOUND, versionId);
        }
        return toVersionVO(version);
    }

    /** 回滚：以快照正文再走一次保存，版本号继续递增 */
    @Transactional(rollbackFor = Exception.class)
    public void restore(String versionId) {
        String userId = LoginContext.userId();
        WbDocVersionDO version = versionMapper.selectByIdAndUser(userId, versionId);
        if (Objects.isNull(version)) {
            throw new BizException(ErrorCodeEnum.DOC_VERSION_NOT_FOUND, versionId);
        }
        WbDocDO doc = getOwned(version.getDocId(), userId);
        DocREQ req = new DocREQ();
        req.setDocId(doc.getDocId());
        req.setDocTitle(doc.getDocTitle());
        req.setDocType(doc.getDocType());
        req.setCategory(doc.getCategory());
        req.setTags(doc.getTags());
        req.setSummary(doc.getSummary());
        req.setContent(version.getContent());
        req.setSourceKbId(doc.getSourceKbId());
        req.setState(doc.getState());
        req.setChangeNote(StrUtil.format("回滚到 v{}", version.getVersionNo()));
        update(req);
        log.info("[doc.restore] userId={} docId={} fromVersion={}", userId, doc.getDocId(), version.getVersionNo());
    }

    /** 归属校验：按 (ID, user) 查，查不到即 404 不泄露他人资源是否存在 */
    public WbDocDO getOwned(String docId, String userId) {
        WbDocDO doc = docMapper.selectByIdAndUser(userId, docId);
        if (Objects.isNull(doc)) {
            throw new BizException(ErrorCodeEnum.DOC_NOT_FOUND, docId);
        }
        return doc;
    }

    /** 富文本转纯文本：去脚本样式 → 去标签 → 还原实体，用于索引与字数统计 */
    public String toPlainText(String content, String docType) {
        String raw = StrUtil.nullToEmpty(content);
        if (!DocTypeEnum.RICH.getCode().equals(docType)) {
            return raw;
        }
        String withoutBlock = BLOCK_PATTERN.matcher(raw).replaceAll(" ");
        String plain = TAG_PATTERN.matcher(withoutBlock).replaceAll(" ");
        for (int i = 0; i < ENTITY_SOURCES.length; i++) {
            plain = plain.replace(ENTITY_SOURCES[i], ENTITY_TARGETS[i]);
        }
        return plain.replaceAll("\\s+", " ").trim();
    }

    /** 写一条版本快照 */
    private void insertVersion(WbDocDO doc, int versionNo, String changeNote) {
        WbDocVersionDO version = new WbDocVersionDO()
                .setVersionId(IdGenerator.generate(IdPrefixConst.DOC_VERSION))
                .setDocId(doc.getDocId())
                .setUserId(doc.getUserId())
                .setVersionNo(versionNo)
                .setDocTitle(doc.getDocTitle())
                .setContent(doc.getContent())
                .setChangeNote(changeNote)
                .setCreatedBy(doc.getUserId());
        versionMapper.insert(version);
    }

    /** 同步索引：失败只告警，PG 为准 */
    private void syncIndex(WbDocDO doc) {
        docIndexMapper.upsert(doc.getDocId(), doc.getUserId(), doc.getDocTitle(), doc.getCategory(),
                doc.getContentText(), Objects.requireNonNullElse(doc.getWordCount(), 0), System.currentTimeMillis());
    }

    /** 字数：中文按字符，英文按词近似，取纯文本长度剔除空白 */
    private int wordCount(String content) {
        return StrUtil.nullToEmpty(content).replaceAll("\\s+", "").length();
    }

    private void requireLength(String content) {
        if (content.length() > DocConst.CONTENT_MAX) {
            throw new BizException(ErrorCodeEnum.DOC_CONTENT_TOO_LONG, content.length());
        }
    }

    /** 用户内文档标题唯一 */
    private void requireUniqueTitle(String userId, String docTitle, String excludeId) {
        if (docMapper.countByTitle(userId, docTitle, excludeId) > 0) {
            throw new BizException(ErrorCodeEnum.DOC_TITLE_CONFLICT, docTitle);
        }
    }

    private String resolveType(String docType) {
        if (StrUtil.isBlank(docType)) {
            return DocTypeEnum.MARKDOWN.getCode();
        }
        for (DocTypeEnum type : DocTypeEnum.values()) {
            if (type.getCode().equals(docType)) {
                return type.getCode();
            }
        }
        throw new BizException(ErrorCodeEnum.PARAM_INVALID, "不支持的文档格式");
    }

    private String resolveState(String state) {
        if (StrUtil.isBlank(state)) {
            return DocStateEnum.DRAFT.getCode();
        }
        for (DocStateEnum item : DocStateEnum.values()) {
            if (item.getCode().equals(state)) {
                return item.getCode();
            }
        }
        throw new BizException(ErrorCodeEnum.PARAM_INVALID, "不支持的文档状态");
    }

    private DocVO toVO(WbDocDO doc) {
        return toVO(doc, false);
    }

    private DocVO toVO(WbDocDO doc, boolean withContent) {
        DocVO vo = new DocVO();
        vo.setDocId(doc.getDocId());
        vo.setUserId(doc.getUserId());
        vo.setDocTitle(doc.getDocTitle());
        vo.setDocType(doc.getDocType());
        vo.setCategory(doc.getCategory());
        vo.setTags(doc.getTags());
        vo.setSummary(doc.getSummary());
        vo.setWordCount(doc.getWordCount());
        vo.setVersionNo(doc.getVersionNo());
        vo.setSourceKbId(doc.getSourceKbId());
        vo.setState(doc.getState());
        vo.setCreatedAt(TimeUtil.toMillis(doc.getCreatedAt()));
        vo.setUpdatedAt(TimeUtil.toMillis(doc.getUpdatedAt()));
        if (withContent) {
            vo.setContent(doc.getContent());
        }
        return vo;
    }

    private DocVersionVO toVersionVO(WbDocVersionDO version) {
        DocVersionVO vo = new DocVersionVO();
        vo.setVersionId(version.getVersionId());
        vo.setDocId(version.getDocId());
        vo.setVersionNo(version.getVersionNo());
        vo.setDocTitle(version.getDocTitle());
        vo.setContent(version.getContent());
        vo.setChangeNote(version.getChangeNote());
        vo.setCreatedAt(TimeUtil.toMillis(version.getCreatedAt()));
        return vo;
    }
}
