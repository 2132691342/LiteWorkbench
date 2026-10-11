package com.liteworkbench.agent;

import com.liteworkbench.domain.dto.RunEventDTO;
import reactor.core.publisher.Flux;

/** Agent 运行时门面：装配 ReActAgent 并输出归一化事件流 */
public interface AgentRuntime {

    /** 发起一次流式运行，事件按产生顺序输出 */
    Flux<RunEventDTO> stream(RunContext context);
}
