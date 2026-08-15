package qg.qgent.orchestration.tool;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 构造受控 git diff / rev-parse 启动向量的包私有工具。
 * <p>
 * Git 进程由同包的 {@link SandboxProcessRunner} 启动，不经过 {@link SandboxCommandPolicy}
 * 白名单端口（该端口只放行 mvn/npm/gradle 等测试命令），因此 base ref 校验必须足够严格，
 * 从结构上排除 option 注入、范围操作符与越权引用：
 * <ul>
 *   <li>base 视为外部输入（可能来自 DB 的 baseCommit/headCommit），只允许字母/数字开头，
 *       后续字符限字母、数字、{@code _ - . /}；</li>
 *   <li>拒绝以 {@code -} 开头（防止被 git 解析为选项，如 {@code -U}、{@code --output=}）；</li>
 *   <li>拒绝含 {@code ..}、{@code @\{}、空白、冒号等字符（防止范围/引用操作符与歧义）；</li>
 *   <li>本类只构造只读命令，不包含 commit/push/MR 等任何 Git 写操作。</li>
 * </ul>
 * 合法 base 包括完整/缩写 SHA、普通分支名（{@code main}）与 feature 分支名
 * （{@code feat/task-x}）。{@link #diffCommand} 使用 {@code --no-pager --no-color --no-ext-diff}
 * 保证输出为稳定的纯文本，避免分页、ANSI 颜色与外部 diff 驱动介入。
 */
final class GitDiffCommandBuilder {

    /**
     * 保守的 ref 字符模式：字母/数字开头，后续只允许字母、数字、{@code _ - . /}。
     */
    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");

    private GitDiffCommandBuilder() {
    }

    /**
     * base ref 是否可作为 git diff / rev-parse 的位置参数安全使用。
     */
    static boolean isValidBase(String base) {
        if (base == null || base.isBlank()) {
            return false;
        }
        if (!SAFE_REF.matcher(base).matches()) {
            return false;
        }
        return !base.contains("..") && !base.contains("@{");
    }

    /**
     * 构造 {@code git diff} 启动向量，base 为相对 base ref 的工作树变更。
     *
     * @param base 已通过 {@link #isValidBase} 校验的 base ref（SHA 或分支名）。
     * @return 完整启动 argv。
     * @throws IllegalArgumentException base 非法（编程错误或 DB 数据被破坏时的防御性失败）。
     */
    static List<String> diffCommand(String base) {
        if (!isValidBase(base)) {
            throw new IllegalArgumentException("unsafe git diff base: " + base);
        }
        return List.of("git", "--no-pager", "diff", "--no-color", "--no-ext-diff", base);
    }

    /**
     * 构造 {@code git rev-parse} 启动向量，把 base / HEAD 解析为真实提交 SHA。
     *
     * @param ref 已通过 {@link #isValidBase} 校验的 ref（base 或 HEAD）。
     * @return 完整启动 argv。
     * @throws IllegalArgumentException ref 非法时抛出。
     */
    static List<String> revParseCommand(String ref) {
        if (!isValidBase(ref)) {
            throw new IllegalArgumentException("unsafe git ref: " + ref);
        }
        return List.of("git", "rev-parse", ref);
    }
}
