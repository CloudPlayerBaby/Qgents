package qg.qgent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.ExecutionLogEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.mapper.ExecutionLogMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.orchestration.ExecutionContentSanitizer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * TaskRun 日志唯一写入口。日志先插入 execution_logs，再通过项目事件流发布实时进度，
 * 从而历史游标与 SSE 使用同一条持久化序列。内容统一限长和脱敏，禁止把 Prompt、凭据
 * 或宿主机路径写入用户可见日志。
 */
@Service
public class TaskRunLogService {
    private static final int MAX_CONTENT_LENGTH = 4000;

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

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max) + "…";
    }
}
