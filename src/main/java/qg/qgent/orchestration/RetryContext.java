package qg.qgent.orchestration;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 传给后续 Agent 的受控失败上下文。只包含稳定码、限长摘要、脱敏失败项和相对文件名，
 * 不携带原始日志、命令输出、凭证或宿主机路径。
 */
@Data
public class RetryContext {
    private String failureCode;
    private String failureSummary;
    private List<String> failures = new ArrayList<>();
    private List<String> modifiedFiles = new ArrayList<>();
    /** 同一 Task/Step 重试时按相对路径继承的连续补丁失败次数。 */
    private Map<String, Integer> patchFailureCounts = new LinkedHashMap<>();
    /**
     * 上一轮 Review 实际激活的 Skill ID。回修写 Agent 必须在本次 TaskRun 内重新校验并读取正文，
     * 此字段不保存正文，也不能绕过项目权限和发布状态校验。
     */
    private List<UUID> reviewActivatedSkillIds = new ArrayList<>();
    private String instruction;
    /**
     * 是否为质量修复步骤：上一轮 Test/Review 以 {@code FAILED_QUALITY} 打回 Coding 修复。
     * 仅质量修复步骤允许在零写入时用「目标已满足」收敛（satisfied 兜底）；普通 MUTATE 步骤
     * 仍要求真实变更，避免内容错误被文件存在性掩盖而误判成功。
     */
    private boolean qualityRepair;
    /**
     * 上一轮 Review 给出的结构化修复动作（如 ENSURE_TRAILING_NEWLINE）。传给回修的 Coding Agent，
     * 作为优先执行的受控修复动作（如先调 ensure_trailing_newline 追加末尾换行），避免 AI 手工
     * 重拼文件或反复生成错误 patch。可为 null（无结构化修复动作时回退到按 finding 自行修复）。
     */
    private String repairAction;
    /**
     * 修复动作目标文件（Workspace 相对路径），与 {@link #repairAction} 配套。
     */
    private String repairFile;
}
