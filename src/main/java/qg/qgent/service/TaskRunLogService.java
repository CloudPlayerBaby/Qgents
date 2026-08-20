package qg.qgent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.ExecutionLogEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.mapper.ExecutionLogMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.orchestration.ExecutionContentSanitizer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * TaskRun 日志唯一写入口。日志先插入 execution_logs，再通过项目事件流发布实时进度，
 * 从而历史游标与 SSE 使用同一条持久化序列。内容统一限长和脱敏，禁止把 Prompt、凭据
 * 或宿主机路径写入用户可见日志。
 */
@Service
public class TaskRunLogService {
    private static final int MAX_CONTENT_LENGTH = 4000;
    private static final int MAX_LINES_PER_APPEND = 200;

    private final ExecutionLogMapper logMapper;
    private final TaskMapper taskMapper;
    private final TaskRunMapper taskRunMapper;
    private final EventService eventService;

    public TaskRunLogService(ExecutionLogMapper logMapper, TaskMapper taskMapper, EventService eventService) {
        this(logMapper, taskMapper, null, eventService);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskRunLogService(ExecutionLogMapper logMapper, TaskMapper taskMapper,
                             TaskRunMapper taskRunMapper, EventService eventService) {
        this.logMapper = logMapper;
        this.taskMapper = taskMapper;
        this.taskRunMapper = taskRunMapper;
        this.eventService = eventService;
    }

    /**
     * 追加一条运行日志。调用方已经完成 TaskRun 状态变更时，必须在同一事务中调用。
     */
    @Transactional
    public ExecutionLogEntity append(TaskRunEntity run, String entryType, String node, String content) {
        if (run == null || run.getId() == null) {
            return null;
        }
        // 生产环境锁住父运行行；测试/兼容构造器没有 TaskRunMapper 时仍可验证脱敏与事件顺序。
        if (taskRunMapper != null) {
            taskRunMapper.selectByIdForUpdate(run.getId());
        }
        String safeType = switch (entryType == null ? "" : entryType.toUpperCase()) {
            case "SYSTEM", "TERMINAL" -> entryType.toUpperCase();
            default -> "EXECUTION";
        };
        String safeNode = truncate(ExecutionContentSanitizer.sanitize(node == null ? "" : node.strip()), 64);
        String safeContent = truncate(ExecutionContentSanitizer.sanitize(content == null ? "" : content),
                MAX_CONTENT_LENGTH);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ExecutionLogEntity log = new ExecutionLogEntity();
        log.setId(UuidV7.next());
        log.setTaskRunId(run.getId());
        log.setSequenceNo(logMapper.nextSequence(run.getId()) + 1L);
        log.setEntryType(safeType);
        log.setNode(safeNode.isBlank() ? null : safeNode);
        log.setContent(safeContent);
        log.setCreatedAt(now);
        logMapper.insert(log);

        TaskEntity task = run.getTaskId() == null ? null : taskMapper.selectById(run.getTaskId());
        UUID groupId = task == null ? null : task.getRequirementGroupId();
        eventService.publish(run.getProjectId(), groupId, "task-run.step.progress", run.getId().toString(),
                TaskEventPayloads.taskRunLog(run, log));
        return log;
    }

    /** 将 Worker 的 stdout/stderr 按行转存，避免一次超长输出遮蔽游标和前端实时展示。 */
    @Transactional
    public void appendWorkerOutput(TaskRunEntity run, String stream, String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        String node = "WORKER/" + (stream == null || stream.isBlank() ? "OUTPUT" : stream.toUpperCase());
        List<String> lines = output.lines().limit(MAX_LINES_PER_APPEND).toList();
        for (String line : lines) {
            append(run, "EXECUTION", node, line);
        }
    }

    /**
     * 持久化 Test 的结构化结果摘要。命令、退出码和摘要必须与 stdout/stderr 同属一个
     * TaskRun 日志序列，前端只读取现有 logs 接口即可还原“执行了什么、结果如何、为什么失败”。
     * 原始 stdout/stderr 仍由 {@link #appendWorkerOutput(TaskRunEntity, String, String)} 分行写入，
     * 失败项只保留脱敏后的有限摘要，避免把完整 LLM 响应或敏感命令参数写入日志。
     */
    @Transactional
    public void appendVerificationResult(TaskRunEntity run, TestResult result) {
        if (result == null) {
            return;
        }
        String verificationMode = result.getVerificationMode() == null || result.getVerificationMode().isBlank()
                ? "UNKNOWN" : result.getVerificationMode();
        String command = result.getCommand() == null || result.getCommand().isBlank()
                ? "未执行命令" : result.getCommand();
        String outcome = result.isSuccess() ? "PASSED" : "FAILED";
        int failureCount = result.getFailures() == null ? 0 : (int) result.getFailures().stream()
                .filter(Objects::nonNull).count();
        StringBuilder summary = new StringBuilder("验证结果：")
                .append(outcome)
                .append("；验证方式：").append(verificationMode)
                .append("；命令：").append(command)
                .append("；exitCode：").append(result.getExitCode())
                .append("；失败项数量：").append(failureCount);
        append(run, "EXECUTION", "TEST", summary.toString());
        if (failureCount > 0) {
            append(run, "EXECUTION", "TEST/FAILURE", "验证失败项详情已隐藏，请查看受控执行记录");
        }
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max) + "…";
    }
}
