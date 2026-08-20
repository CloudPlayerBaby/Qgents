package qg.qgent.orchestration;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private String instruction;
}
