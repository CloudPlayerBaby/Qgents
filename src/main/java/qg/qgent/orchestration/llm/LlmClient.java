package qg.qgent.orchestration.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 供 Agent 调用的 LLM 端口。
 * <p>
 * {@link #complete} 两个重载是纯文本补全（Plan/Test Agent 以及灰度期 legacy 协议使用），
 * 把 Spring AI 的调用细节隔离在本包内；{@link #nextToolTurn} 是原生 Tool Calling 路径
 * （阶段 B），按计划文档 AI_PATCH_SSE_MODIFICATION_PLAN 有意放宽「llm 包隔离」，直接以
 * Spring AI 的 {@link Message} 与 {@link ToolCallback} 表达原生 tool-call / tool-result
 * 历史，换取官方 Tool Calling 路径稳定性（避免自建文本 JSON 协议与 thinking 模式
 * reasoning_content 回传的脆弱性）。Spring AI 的具体调用与工具分发仍封装在
 * {@link SpringAiChatLlmClient} 内部，Agent 只透传历史与工具契约。
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

    /**
     * 执行一轮原生 Tool Calling 并返回结果。
     * <p>
     * 客户端把 system 与 history 组装为一次模型调用（history 不含 system，客户端自行前置），
     * 若模型请求工具则同步执行并把工具结果追加到返回的历史中（关联的 tool call id）。
     * 返回的 {@link ToolTurnResult} 三种状态：最终文本 / 继续循环 / 基础设施中止。
     *
     * @param systemPrompt 系统角色指令。
     * @param history      完整对话历史（不含 system），首轮为模型无 system 的初始消息。
     * @param tools        本轮允许使用的白名单工具回调（决定模型可见的工具 schema）。
     * @return 本轮结果；从不为 null。见 {@link ToolTurnResult} 三态语义。
     * @throws RuntimeException 模型调用本身失败（网络、鉴权）时抛出，由调用方映射基础设施失败。
     */
    ToolTurnResult nextToolTurn(String systemPrompt, List<Message> history, List<ToolCallback> tools);
}
