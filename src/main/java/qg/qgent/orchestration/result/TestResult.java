package qg.qgent.orchestration.result;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Test Agent 的结构化产出：是否通过、真实 exit code、执行的命令、脱敏输出与失败项。
 * <p>
 * success 必须基于 ExecutionPort 返回的真实 exit code（exitCode == 0 才算通过），
 * 不允许凭记忆或 LLM 声称通过。stdout/stderr 只存已脱敏文本，不承载 Secret。
 * failures 供 Coding Agent 判断下一步应修复什么；needsCodingFix=false 且未通过时
 * 视为不可自动修复，由状态机判 Task FAILED 而非回 Coding 重试。
 */
@Data
public class TestResult {
    /**
     * 是否通过验收，基于真实 exit code。
     */
    private boolean success;
    /**
     * 真实 exit code；-1 表示未产生可用的执行结果。
     */
    private int exitCode;
    /**
     * 实际执行的测试命令（白名单解析结果）。
     */
    private String command;
    /**
     * 验证方式：COMMAND（执行白名单测试命令）、FILE_ASSERTION（纯文件断言）或 NONE（无法验证）。
     */
    private String verificationMode;
    /**
     * 已脱敏的 stdout。
     */
    private String stdout;
    /**
     * 已脱敏的 stderr。
     */
    private String stderr;
    /**
     * 分析摘要。
     */
    private String summary;
    /**
     * 失败项列表。
     */
    private List<Failure> failures = new ArrayList<>();
    /**
     * 是否需要 Coding Agent 修复；false 且未通过时不可自动修复。
     */
    private boolean needsCodingFix;

    /**
     * 单个失败项。
     */
    @Data
    public static class Failure {
        private String name;
        private String reason;
        private String severity;
    }
}
