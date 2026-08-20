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
}
