package com.liteworkbench.api;

import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.dto.SessionPageREQ;
import com.liteworkbench.domain.dto.SessionREQ;
import com.liteworkbench.domain.vo.MessageVO;
import com.liteworkbench.domain.vo.SessionVO;
import com.liteworkbench.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 会话接口：登录即可用，按 user_id 归属隔离 */
@RestController
@RequestMapping("/api/v1/session")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    /** 分页查询 */
    @GetMapping("/page")
    public R<PageResult<SessionVO>> page(SessionPageREQ req) {
        return R.ok(sessionService.page(req, LoginContext.userId()));
    }

    /** 详情 */
    @GetMapping("/detail/{sessionId}")
    public R<SessionVO> detail(@PathVariable String sessionId) {
        return R.ok(sessionService.detail(sessionId, LoginContext.userId()));
    }

    /** 新建会话 */
    @Idempotent(prefix = "session:create")
    @PostMapping("/create")
    public R<String> create(@RequestBody @Valid SessionREQ req) {
        return R.ok(sessionService.create(req, LoginContext.userId()));
    }

    /** 修改标题 / 模型 / 系统提示词 */
    @Idempotent(prefix = "session:update")
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid SessionREQ req) {
        sessionService.update(req, LoginContext.userId());
        return R.ok();
    }

    /** 逻辑删除 */
    @Idempotent(prefix = "session:delete")
    @PostMapping("/delete/{sessionId}")
    public R<Void> delete(@PathVariable String sessionId) {
        sessionService.delete(sessionId, LoginContext.userId());
        return R.ok();
    }

    /** 消息列表 */
    @GetMapping("/messages/{sessionId}")
    public R<List<MessageVO>> messages(@PathVariable String sessionId) {
        return R.ok(sessionService.messages(sessionId, LoginContext.userId()));
    }
}
