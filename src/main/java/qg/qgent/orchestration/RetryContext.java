package qg.qgent.orchestration;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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
    private String instruction;
}
