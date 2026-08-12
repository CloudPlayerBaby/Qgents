package qg.qgent.orchestration.tool;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 沙箱未接入时的占位实现：任何执行请求都返回明确"未就绪"结果，
 * 绝不落到宿主机执行，保证最小权限。
 */
@Component
public class DisabledExecutionPort implements ExecutionPort {

    @Override
    public ExecutionResult execute(UUID workspaceId, List<String> command, Duration timeout) {
        return ExecutionResult.unavailable();
    }
}
