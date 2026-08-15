package qg.qgent.sandboxworker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandboxworker.api.ExecutionLogEntryResponse;
import qg.qgent.sandboxworker.api.ExecutionLogsResponse;
import qg.qgent.sandboxworker.api.ToolExecutionRequest;
import qg.qgent.sandboxworker.api.ToolExecutionResponse;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.persistence.ToolExecutionEntity;
import qg.qgent.sandboxworker.persistence.ToolExecutionLogEntity;
import qg.qgent.sandboxworker.persistence.ToolExecutionLogMapper;
import qg.qgent.sandboxworker.persistence.ToolExecutionMapper;
import qg.qgent.sandboxworker.runtime.SandboxAllocation;
import qg.qgent.sandboxworker.runtime.WorkspacePathResolver;
import qg.qgent.sandboxworker.tool.ToolContext;
import qg.qgent.sandboxworker.tool.ToolRegistry;
import qg.qgent.sandboxworker.tool.ToolResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 统一管理结构化工具的异步执行、取消、日志和 MySQL 持久化。
 * 提交接口只负责建立 QUEUED 记录，实际工具在 Worker 固定线程池中运行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutionService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private final SandboxService sandboxes;
    private final WorkspacePathResolver paths;
    private final ToolRegistry tools;
    private final ToolExecutionMapper executionMapper;
    private final ToolExecutionLogMapper logMapper;
    private final ObjectMapper objectMapper;
    private final SandboxWorkerProperties properties;
    private final ExecutorService sandboxExecutionPool;
    private final Clock clock;
    private final ConcurrentMap<UUID, Thread> activeThreads = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> logLocks = new ConcurrentHashMap<>();

    /**
     * Worker 重启后把无法恢复的排队中和运行中记录标记为中断。
     */
    @PostConstruct
    void markInterruptedExecutions() {
        executionMapper.markInterrupted(properties.getWorkerId(), utc(clock.instant()));
    }

    /**
     * 创建持久化执行记录并投递后台线程。
     * executionId 是执行资源编号，重复使用时返回冲突，不提供请求重放语义。
     */
    public ToolExecutionResponse submit(UUID sandboxId, ToolExecutionRequest request) {
        // executionID 不能重复
        if (executionMapper.selectById(request.getExecutionId().toString()) != null) {
            throw new WorkerException(HttpStatus.CONFLICT,
                    "EXECUTION_ID_CONFLICT", "执行编号已经存在");
        }

        // 沙箱需要存在
        SandboxAllocation sandbox = sandboxes.findAllocation(sandboxId);
        // 需要仓库的工具必须要给仓库
        validateRepository(request);
        String argumentsJson = json(request.getArguments());
        ToolExecutionEntity entity = createEntity(sandboxId, request, argumentsJson);
        try {
            executionMapper.insert(entity);
        } catch (RuntimeException exception) {
            if (executionMapper.selectById(entity.getId()) != null) {
                throw new WorkerException(HttpStatus.CONFLICT,
                        "EXECUTION_ID_CONFLICT", "执行编号已经存在");
            }
            throw exception;
        }
        append(entity.getId(), "SYSTEM", "工具执行已进入队列：" + request.getTool());
        log.info("tool queued executionId={} sandboxId={} tool={} repositoryId={}",
                entity.getId(), sandboxId, request.getTool(), request.getRepositoryId());
        // 投给后台开始跑
        try {
            sandboxExecutionPool.execute(() -> run(entity.getId(), sandbox, request));
        } catch (RejectedExecutionException exception) {
            executionMapper.rejectQueued(entity.getId(), properties.getWorkerId(),
                    "Worker 执行队列不可用", utc(clock.instant()));
            throw new WorkerException(HttpStatus.SERVICE_UNAVAILABLE,
                    "EXECUTION_QUEUE_UNAVAILABLE", "Worker 执行队列暂时不可用");
        }
        return response(entity);
    }

    /**
     * 查询一条持久化工具执行记录。
     */
    public ToolExecutionResponse find(UUID executionId) {
        return response(require(executionId));
    }

    /**
     * 取消仍在排队或运行的执行。
     * 数据库状态先切换为 CANCELLED，再中断本 Worker 中对应的执行线程。
     */
    public ToolExecutionResponse cancel(UUID executionId) {
        LocalDateTime finishedAt = utc(clock.instant());
        if (executionMapper.markCancelled(executionId.toString(), properties.getWorkerId(), finishedAt) == 0) {
            ToolExecutionEntity current = require(executionId);
            if (!properties.getWorkerId().equals(current.getOwnerWorkerId())) {
                throw new WorkerException(HttpStatus.CONFLICT,
                        "EXECUTION_OWNED_BY_OTHER_WORKER", "执行不属于当前 Worker");
            }
            throw new WorkerException(HttpStatus.CONFLICT,
                    "EXECUTION_NOT_CANCELLABLE", "当前执行状态不可取消：" + current.getStatus());
        }
        Thread thread = activeThreads.get(executionId);
        if (thread != null) {
            thread.interrupt();
        }
        append(executionId.toString(), "SYSTEM", "工具执行已取消");
        log.info("tool cancel executionId={}", executionId);
        logLocks.remove(executionId.toString());
        return response(require(executionId));
    }

    /**
     * 取消指定 Sandbox 中仍处于活动状态的全部工具执行。
     */
    public void cancelBySandbox(UUID sandboxId) {
        executionMapper.selectActiveIdsBySandbox(sandboxId.toString(), properties.getWorkerId()).forEach(id -> {
            try {
                cancel(UUID.fromString(id));
            } catch (WorkerException exception) {
                if (!"EXECUTION_NOT_CANCELLABLE".equals(exception.getCode())) {
                    throw exception;
                }
            }
        });
    }

    /**
     * 按执行内递增序号分页读取日志。
     */
    public ExecutionLogsResponse logs(UUID executionId, long after, int limit) {
        require(executionId);
        List<ExecutionLogEntryResponse> items = logMapper
                .selectAfter(executionId.toString(), after, Math.max(1, Math.min(limit, 1000)))
                .stream()
                .map(row -> new ExecutionLogEntryResponse(row.getSequenceNo(), row.getStream(), row.getContent(),
                        row.getCreatedAt().toInstant(ZoneOffset.UTC)))
                .toList();
        long cursor = items.isEmpty() ? after : items.get(items.size() - 1).getSequence();
        return new ExecutionLogsResponse(items, cursor);
    }

    private void run(String executionId, SandboxAllocation sandbox, ToolExecutionRequest request) {
        Instant started = clock.instant();
        if (executionMapper.markRunning(executionId, properties.getWorkerId(), utc(started)) == 0) {
            return;
        }
        UUID id = UUID.fromString(executionId);
        activeThreads.put(id, Thread.currentThread());
        ToolExecutionEntity running = executionMapper.selectById(executionId);
        if (running == null || !"RUNNING".equals(running.getStatus())) {
            activeThreads.remove(id, Thread.currentThread());
            return;
        }
        append(executionId, "SYSTEM", "开始执行工具：" + request.getTool());
        log.info("tool start executionId={} sandboxId={} tool={} repositoryId={}",
                executionId, sandbox.getId(), request.getTool(), request.getRepositoryId());

        String status;
        Integer exitCode = null;
        String resultJson = null;
        String failureReason = null;
        try {
            Duration timeout = timeout(request.getTimeoutSeconds(), sandbox.getExecutionTimeout());
            ToolContext context = context(sandbox, request.getRepositoryId(), timeout);
            ToolResult result = tools.execute(request.getTool(), context, request.getArguments());
            result.getStandardOutput().forEach(line -> append(executionId, "STDOUT", line));
            result.getStandardError().forEach(line -> append(executionId, "STDERR", line));
            exitCode = result.getExitCode();
            resultJson = json(result.getResult());
            status = exitCode == null || exitCode == 0 ? "SUCCEEDED" : "FAILED";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status = "TIMED_OUT";
            failureReason = "工具执行超时或被中断";
            log.warn("tool interrupted executionId={} tool={}", executionId, request.getTool());
        } catch (RuntimeException exception) {
            status = "FAILED";
            failureReason = safeMessage(exception);
            log.error("tool failed executionId={} sandboxId={} tool={} failureReason={}",
                    executionId, sandbox.getId(), request.getTool(), failureReason);
        } finally {
            activeThreads.remove(id, Thread.currentThread());
        }

        executionMapper.finishIfRunning(executionId, properties.getWorkerId(), status,
                exitCode, resultJson, failureReason,
                utc(clock.instant()));
        ToolExecutionEntity completed = executionMapper.selectById(executionId);
        if (completed != null) {
            append(executionId, "SYSTEM", "工具执行结束，状态：" + completed.getStatus());
        }
        logLocks.remove(executionId);
        log.info("tool done executionId={} sandboxId={} tool={} status={} exitCode={} durationMs={} failureReason={}",
                executionId, sandbox.getId(), request.getTool(), status, exitCode,
                Duration.between(started, clock.instant()).toMillis(), failureReason);
    }

    private void validateRepository(ToolExecutionRequest request) {
        if (tools.requiresRepository(request.getTool()) && request.getRepositoryId() == null) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "REPOSITORY_REQUIRED", "该工具必须指定仓库编号");
        }
    }

    private ToolContext context(SandboxAllocation sandbox, UUID repositoryId, Duration timeout) {
        if (repositoryId == null) {
            return new ToolContext(sandbox, null, null, "/workspace", timeout);
        }
        return new ToolContext(sandbox, repositoryId, paths.resolveRepositoryLocal(sandbox, repositoryId),
                paths.resolveRepositoryContainer(sandbox, repositoryId), timeout);
    }

    private ToolExecutionEntity createEntity(UUID sandboxId, ToolExecutionRequest request, String argumentsJson) {
        ToolExecutionEntity entity = new ToolExecutionEntity();
        entity.setId(request.getExecutionId().toString());
        entity.setOwnerWorkerId(properties.getWorkerId());
        entity.setSandboxId(sandboxId.toString());
        entity.setRepositoryId(request.getRepositoryId() == null ? null : request.getRepositoryId().toString());
        entity.setToolName(request.getTool());
        entity.setArgumentsJson(argumentsJson);
        entity.setStatus("QUEUED");
        entity.setCreatedAt(utc(clock.instant()));
        return entity;
    }

    private ToolExecutionEntity require(UUID executionId) {
        ToolExecutionEntity entity = executionMapper.selectById(executionId.toString());
        if (entity == null) {
            throw new WorkerException(HttpStatus.NOT_FOUND,
                    "EXECUTION_NOT_FOUND", "工具执行记录不存在");
        }
        return entity;
    }

    private void append(String executionId, String stream, String content) {
        Object lock = logLocks.computeIfAbsent(executionId, ignored -> new Object());
        synchronized (lock) {
            ToolExecutionLogEntity log = new ToolExecutionLogEntity();
            log.setExecutionId(executionId);
            log.setSequenceNo(logMapper.selectMaxSequence(executionId) + 1);
            log.setStream(stream);
            log.setContent(content.length() <= 16000 ? content : content.substring(0, 16000));
            log.setCreatedAt(utc(clock.instant()));
            logMapper.insert(log);
        }
    }

    private Duration timeout(Long seconds, Duration sandboxLimit) {
        Duration requested = seconds == null ? sandboxLimit : Duration.ofSeconds(seconds);
        Duration effective = requested.compareTo(sandboxLimit) <= 0 ? requested : sandboxLimit;
        return effective.compareTo(properties.getMaxExecutionTimeout()) <= 0
                ? effective
                : properties.getMaxExecutionTimeout();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "TOOL_ARGUMENT_INVALID", "工具参数无法序列化");
        }
    }

    private Map<String, Object> map(String value) {
        if (value == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of("raw", value);
        }
    }

    private String safeMessage(RuntimeException exception) {
        return exception instanceof WorkerException && exception.getMessage() != null
                ? exception.getMessage()
                : "工具执行失败";
    }

    private ToolExecutionResponse response(ToolExecutionEntity entity) {
        return new ToolExecutionResponse(UUID.fromString(entity.getId()), entity.getOwnerWorkerId(),
                UUID.fromString(entity.getSandboxId()),
                entity.getRepositoryId() == null ? null : UUID.fromString(entity.getRepositoryId()),
                entity.getToolName(), entity.getStatus(), entity.getExitCode(), map(entity.getResultJson()),
                entity.getFailureReason(), instant(entity.getCreatedAt()), instant(entity.getStartedAt()),
                instant(entity.getFinishedAt()));
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
