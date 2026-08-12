package qg.qgent.orchestration.llm;

import java.util.List;

/**
 * 供 Agent 调用的 LLM 文本补全端口。
 * <p>
 * 把 Spring AI 的具体调用方式隔离在该包内，使 Agent 可针对本接口做单元测试（Mock LLM），
 * 也避免 Agent 直接依赖某一版本的 ChatModel API。多轮工具调用场景由
 * {@link #complete(String, List)} 携带完整对话历史驱动。
 */
public interface LlmClient {

    /**
     * 以 system + user 两条消息调用一次模型，返回模型输出的纯文本。
     *
     * @param systemPrompt 系统角色指令（职责、约束、输出格式）。
     * @param userPrompt   用户输入（任务上下文、文件树、按需读取的文件内容）。
     * @return 模型生成的文本；实现不得返回明文 Secret。
     * @throws RuntimeException 调用失败（网络、鉴权、解析）时抛出，由调用方决定重试语义。
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * 以 system + 对话历史调用一次模型，返回模型输出的纯文本。
     * <p>
     * history 允许 USER/ASSISTANT/TOOL 三种角色：ASSISTANT 为模型上一次输出，
     * TOOL 为工具执行结果，二者都必须回到模型上下文以支撑多轮工具调用。
     *
     * @param systemPrompt 系统角色指令。
     * @param messages     对话历史（含工具结果），不含 system。
     * @return 模型生成的文本。
     * @throws RuntimeException 调用失败时抛出，由调用方决定重试语义。
     */
    String complete(String systemPrompt, List<LlmMessage> messages);
}
