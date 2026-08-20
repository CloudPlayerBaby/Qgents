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
     * Plan 结构化断言（machineAssertions）的确定性校验结果（仅纯文件断言路径产出），作为供
     * Review 判断的"预期信号"，不参与本结果 success 判定——Coding 因合理原因偏离断言时由
     * Review 结合偏差声明做最终裁决。可为空列表。
     */
    private List<FileAssertion> assertionResults = new ArrayList<>();
    /**
     * 环境阻塞失败码：非空表示测试命令已真实执行但非零退出，且被确定性判定为环境/依赖/网络/服务/
     * 超时/构建工具不可用（非本次代码缺陷）。供 Review 在「测试因环境问题未执行」的阻塞下审查代码
     * 逻辑，并在终态如实标注「测试因环境问题未执行」。仅环境阻塞时非空。
     */
    private String environmentFailureCode;

    /**
     * 单个失败项。
     */
    @Data
    public static class Failure {
        private String name;
        private String reason;
        private String severity;
    }

    /**
     * 单条结构化断言的校验事实（机器可信信号，非裁决）。
     */
    @Data
    public static class FileAssertion {
        /** 目标文件（Workspace 相对路径）。 */
        private String file;
        /** 断言类型：EXISTS/EMPTY/LINES_EQ/LINES_GT/LINES_LT/CONTAINS/NOT_CONTAINS。 */
        private String type;
        /** 期望值（Plan 声明）：LINES_* 为行数、CONTAINS/NOT_CONTAINS 为子串；EXISTS/EMPTY 为 null。 */
        private String expected;
        /** 实际观察值（人类可读）。 */
        private String actual;
        /** 是否满足断言。 */
        private boolean passed;
    }
}
