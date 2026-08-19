package qg.qgent.orchestration.agent;

/**
 * Agent 协议失败的错误码：把一次失败从「未知异常」拆分为可追溯、可归类的稳定类别，
 * 随 TaskRun 脱敏摘要落库（阶段 A）。取值与计划文档 AI_PATCH_SSE_MODIFICATION_PLAN §4.3 一致。
 * <p>
 * 分类规则：
 * <ul>
 *   <li>{@link #LLM_FINISH_LENGTH}：模型响应被 max-tokens 截断（finishReason=length），
 *       输出可能是残缺的 JSON 或半截函数调用；</li>
 *   <li>{@link #LLM_TOOL_CALL_MALFORMED}：模型输出的 finalResult 非法（非 JSON、缺必填字段）；</li>
 *   <li>{@link #LLM_TOOL_NOT_ALLOWED}：模型调用了白名单之外的工具；</li>
 *   <li>{@link #LLM_TOOL_ARGUMENT_INVALID}：工具参数校验失败或参数类型无法转换；</li>
 *   <li>{@link #LLM_CONTEXT_LIMIT}：超过最大工具轮次仍无 finalResult，上下文/对话失控。</li>
 *   <li>{@link #CODING_NO_ACTUAL_CHANGE}：模型声明 Coding 成功，但本次运行没有任何
 *       changed=true 的文件或目录写入；这是业务语义失败，不是模型协议失败。</li>
 * </ul>
 * 基础设施失败（LLM 网络错误、Workspace 不可用等）不属于协议失败，沿用 FAILED_INFRASTRUCTURE，
 * 不带本错误码。
 */
public enum ProtocolFailureCode {

    LLM_FINISH_LENGTH,
    LLM_TOOL_CALL_MALFORMED,
    LLM_TOOL_NOT_ALLOWED,
    LLM_TOOL_ARGUMENT_INVALID,
    LLM_CONTEXT_LIMIT,
    CODING_NO_ACTUAL_CHANGE,
    TOOL_PATCH_UNRECOVERABLE
}
