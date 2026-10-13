package com.liteworkbench.service;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.ModelCaller;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.SessionConst;
import com.liteworkbench.domain.constant.StateEnum;
import com.liteworkbench.domain.dto.MessageDocDTO;
import com.liteworkbench.domain.dto.SessionPageREQ;
import com.liteworkbench.domain.dto.SessionREQ;
import com.liteworkbench.domain.entity.WbSessionDO;
import com.liteworkbench.domain.vo.MessageVO;
import com.liteworkbench.domain.vo.SessionVO;
import com.liteworkbench.mapper.MessageIndexMapper;
import com.liteworkbench.mapper.WbSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** 会话：CRUD、归属校验与首轮自动命名，用户只见自己的会话 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    /** 标题生成最大字数，超出截断 */
    private static final int AUTO_TITLE_MAX_CHARS = 24;

    private final WbSessionMapper sessionMapper;
    private final MessageIndexMapper messageIndexMapper;
    private final ModelCaller modelCaller;

    /** 标题生成线程池：低频、静默、失败不影响对话 */
    private final ExecutorService renameExecutor = ThreadUtil.newFixedExecutor(1, 4, "lw-title", true);

    /** 分页查询，仅返回归属用户的会话 */
    public PageResult<SessionVO> page(SessionPageREQ req, String userId) {
        long total = sessionMapper.countPage(req, userId);
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<SessionVO> list = sessionMapper.selectPage(req, userId, req.offset(), req.pageSize())
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 详情，跨用户访问返回 404 */
    public SessionVO detail(String sessionId, String userId) {
        return toVO(getOwned(sessionId, userId));
    }

    /** 新建会话 */
    public String create(SessionREQ req, String userId) {
        WbSessionDO session = new WbSessionDO();
        session.setSessionId(IdGenerator.generate(IdPrefixConst.SESSION));
        session.setUserId(userId);
        session.setTitle(StrUtil.blankToDefault(req.getTitle(), SessionConst.DEFAULT_TITLE));
        session.setProviderCode(req.getProviderCode());
        session.setModelName(req.getModelName());
        session.setSystemPrompt(req.getSystemPrompt());
        session.setState(StateEnum.NORMAL.getCode());
        session.setCreatedBy(userId);
        sessionMapper.insert(session);
        log.info("[session.create] userId={} sessionId={}", userId, session.getSessionId());
        return session.getSessionId();
    }

    /** 更新标题 / 模型 / 系统提示词 */
    public void update(SessionREQ req, String userId) {
        if (StrUtil.isBlank(req.getSessionId())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "sessionId 不能为空");
        }
        getOwned(req.getSessionId(), userId);
        WbSessionDO session = new WbSessionDO();
        session.setSessionId(req.getSessionId());
        session.setTitle(req.getTitle());
        session.setProviderCode(req.getProviderCode());
        session.setModelName(req.getModelName());
        session.setSystemPrompt(req.getSystemPrompt());
        session.setUpdatedBy(userId);
        sessionMapper.update(session);
    }

    /** 逻辑删除 */
    public void delete(String sessionId, String userId) {
        getOwned(sessionId, userId);
        sessionMapper.deleteById(userId, sessionId, userId);
        long removed = messageIndexMapper.deleteBySession(sessionId);
        log.info("[session.delete] sessionId={} userId={} messagesRemoved={}", sessionId, userId, removed);
    }

    /** 首轮对话自动命名：标题仍为默认值时异步调模型生成，失败静默保持默认标题 */
    public void autoRenameIfDefault(WbSessionDO session, String prompt) {
        if (!SessionConst.DEFAULT_TITLE.equals(session.getTitle())) {
            return;
        }
        renameExecutor.submit(() -> {
            try {
                String title = modelCaller.completeQuietly(namingPrompt(prompt));
                String clean = cleanTitle(title);
                if (StrUtil.isBlank(clean)) {
                    return;
                }
                sessionMapper.updateTitle(session.getSessionId(), session.getUserId(), clean);
                log.info("[session.rename] sessionId={} title={}", session.getSessionId(), clean);
            } catch (Exception e) {
                log.warn("[session.rename] 失败 sessionId={} {}", session.getSessionId(), e.getMessage());
            }
        });
    }

    /** 命名提示词：只输出一行短标题，避免模型带引号与解释 */
    private String namingPrompt(String prompt) {
        String head = StrUtil.sub(StrUtil.nullToEmpty(prompt), 0, 400);
        return "为下面这段用户输入起一个中文会话标题，不超过 16 个字，只输出标题本身，"
                + "不要引号、不要标点、不要解释：\n" + head;
    }

    /** 标题清洗：去引号换行与多余空白，超长截断 */
    private String cleanTitle(String title) {
        if (StrUtil.isBlank(title)) {
            return null;
        }
        String clean = title.replaceAll("[\\r\\n\"'“”‘’。，！？!?]", " ").trim();
        clean = StrUtil.sub(clean, 0, AUTO_TITLE_MAX_CHARS);
        return StrUtil.isBlank(clean) ? null : clean;
    }

    /** 会话消息列表，来自消息索引，(ts, messageId) 稳定升序 */
    public List<MessageVO> messages(String sessionId, String userId) {
        getOwned(sessionId, userId);
        return messageIndexMapper.listBySession(sessionId, SessionConst.MESSAGE_LIST_LIMIT, null)
                .stream().map(this::toMessageVO).toList();
    }

    /** 归属校验：先按 (ID, user) 查，查不到即 404 不泄露存在性 */
    public WbSessionDO getOwned(String sessionId, String userId) {
        WbSessionDO session = sessionMapper.selectByIdAndUser(userId, sessionId);
        if (Objects.isNull(session)) {
            throw new BizException(ErrorCodeEnum.SESSION_NOT_FOUND, sessionId);
        }
        return session;
    }

    private SessionVO toVO(WbSessionDO session) {
        SessionVO vo = new SessionVO();
        vo.setSessionId(session.getSessionId());
        vo.setUserId(session.getUserId());
        vo.setTitle(session.getTitle());
        vo.setProviderCode(session.getProviderCode());
        vo.setModelName(session.getModelName());
        vo.setMessageCount(session.getMessageCount());
        vo.setRunCount(session.getRunCount());
        vo.setTotalTokens(session.getTotalTokens());
        vo.setLastActiveAt(TimeUtil.toMillis(session.getLastActiveAt()));
        vo.setState(session.getState());
        vo.setCreatedAt(TimeUtil.toMillis(session.getCreatedAt()));
        return vo;
    }

    private MessageVO toMessageVO(MessageDocDTO message) {
        MessageVO vo = new MessageVO();
        vo.setMessageId(message.messageId());
        vo.setRunId(message.runId());
        vo.setRoleCode(message.role());
        vo.setContent(message.content());
        vo.setCreatedAt(message.ts());
        return vo;
    }


}
