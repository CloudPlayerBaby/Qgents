package qg.qgent.orchestration.result;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Coding Agent 的结构化产出：是否成功、修改文件、变更描述、自检结果与错误。
 * 只描述"已做修改+自检"，不冒充验收结论（验收判定归 Test/Review）。
 */
@Data
public class CodingResult {
    /**
     * 是否成功完成修改。
     */
    private boolean success;
    /**
     * 实际写入 Workspace 的修改文件相对路径列表。
     */
    private List<String> modifiedFiles = new ArrayList<>();
    /** 实际新建的 Workspace 相对目录列表；不代表 Git 文件 Diff。 */
    private List<String> modifiedDirectories = new ArrayList<>();
    /**
     * 指向 DiffService 产出的真实 Diff 引用，Phase 1 为占位。
     */
    private String diffReference;
    /**
     * 修改摘要。
     */
    private String summary;
    /**
     * 结构化变更描述（每条可对应一个文件或一种改动）。
     */
    private List<String> changes = new ArrayList<>();
    /**
     * 编译/构建自检结果。
     */
    private List<SelfCheck> selfChecks = new ArrayList<>();
    /**
     * 错误列表（success=false 时给出原因），不代表验收结果。
     */
    private List<String> errors = new ArrayList<>();
    /**
     * 与计划/验收标准存在差异时的自声明偏差（"差异 + 理由"），供 Review 判断偏差是否合理；
     * 无差异时为空列表。该字段是模型自述文本，仅透传不校验，不代表服务端验收结论。
     */
    private List<String> deviations = new ArrayList<>();

    /**
     * 单次自检。
     */
    @Data
    public static class SelfCheck {
        private String command;
        private int exitCode;
        private boolean ok;
    }
}
