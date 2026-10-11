package com.liteworkbench.service;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.SessionConst;
import com.liteworkbench.domain.constant.StateEnum;
import com.liteworkbench.domain.dto.SessionPageREQ;
import com.liteworkbench.domain.dto.SessionREQ;
import com.liteworkbench.domain.entity.WbMessageDO;
import com.liteworkbench.domain.entity.WbSessionDO;
import com.liteworkbench.domain.vo.MessageVO;
import com.liteworkbench.domain.vo.SessionVO;
import com.liteworkbench.mapper.WbMessageMapper;
import com.liteworkbench.mapper.WbSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 会话：CRUD 与归属校验，用户只见自己的会话 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final WbSessionMapper sessionMapper;
    private final WbMessageMapper messageMapper;

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
        log.info("[session.delete] sessionId={} userId={}", sessionId, userId);
    }

    /** 会话消息列表 */
    public List<MessageVO> messages(String sessionId, String userId) {
        getOwned(sessionId, userId);
        return messageMapper.selectBySession(sessionId).stream().map(this::toMessageVO).toList();
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
        vo.setLastActiveAt(toMillis(session.getLastActiveAt()));
        vo.setState(session.getState());
        vo.setCreatedAt(toMillis(session.getCreatedAt()));
        return vo;
    }

    private MessageVO toMessageVO(WbMessageDO message) {
        MessageVO vo = new MessageVO();
        vo.setMessageId(message.getMessageId());
        vo.setRunId(message.getRunId());
        vo.setRoleCode(message.getRoleCode());
        vo.setContent(message.getContent());
        vo.setCreatedAt(toMillis(message.getCreatedAt()));
        return vo;
    }

    private Long toMillis(java.time.LocalDateTime time) {
        return Objects.isNull(time) ? null : LocalDateTimeUtil.toEpochMilli(time);
    }
}
