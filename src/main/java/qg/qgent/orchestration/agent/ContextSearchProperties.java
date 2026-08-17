package qg.qgent.orchestration.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 上下文检索工具预算配置：持有每次 TaskRun 内 {@link ContextSearchTool} 调用次数上限。
 * <p>
 * 由 {@code app.agent.context-search.max-per-run} 控制，默认 10；测试可直接 new 一个固定值实例。
 */
@Component
public class ContextSearchProperties {

    /**
     * 每次 TaskRun 检索工具调用次数上限。
     */
    private final int maxPerRun;

    public ContextSearchProperties(@Value("${app.agent.context-search.max-per-run:10}") int maxPerRun) {
        this.maxPerRun = Math.max(0, maxPerRun);
    }

    /**
     * 每次 TaskRun 检索工具调用次数上限。
     */
    public int getMaxPerRun() {
        return maxPerRun;
    }
}