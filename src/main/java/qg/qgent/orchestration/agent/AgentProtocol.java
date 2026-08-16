package qg.qgent.orchestration.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent 与 LLM 之间的工具调用协议灰度开关（阶段 B）。
 * <p>
 * {@code app.agent.protocol=native}（默认）使用 Spring AI 原生 Tool Calling（函数调用 +
 * 结构化 tool_calls）；{@code app.agent.protocol=legacy} 使用旧的手写 JSON 文本协议
 * （模型输出 {@code {"toolCall":{...}}}，后端手写解析）。灰度稳定后删除 legacy 路径与
 * 手写 JSON 解析代码。
 * <p>
 * 未知取值按 native 兜底，避免拼写错误把整个编排切成旧协议。
 */
@Component
public class AgentProtocol {

    private final boolean nativeProtocol;

    public AgentProtocol(@Value("${app.agent.protocol:native}") String protocol) {
        this.nativeProtocol = !"legacy".equalsIgnoreCase(protocol);
    }

    /**
     * 是否使用原生 Tool Calling 协议。
     */
    public boolean isNative() {
        return nativeProtocol;
    }

    /**
     * 测试与默认构建用的无 Spring 工厂：默认原生协议。
     */
    public static AgentProtocol nativeDefault() {
        return new AgentProtocol("native");
    }
}
