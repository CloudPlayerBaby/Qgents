package qg.qgent.sandboxworker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 执行结构化工具，并把请求、最终结果和日志写入 MySQL。
 * 相同 executionId 和相同请求返回原结果，不会重复执行写操作。
 */
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
    private final Clock clock;

    /**
     * Worker 重启后把无法继续追踪的排队中和运行中记录标记为中断。
     */
    @PostConstruct
    void markInterruptedExecutions() {
        executionMapper.markInterrupted(utc(clock.instant()));
    }

    /**
     * 同步执行一项简单工具。进程命令仍受沙箱执行超时限制，HTTP 返回即代表该次工具已经结束。
     */
    public ToolExecutionResponse execute(UUID sandboxId, ToolExecutionRequest request) {
        String argumentsJson = json(request.getArguments());
        String requestHash = hash(sandboxId, request, argumentsJson);
        ToolExecutionEntity existing = executionMapper.selectById(request.getExecutionId().toString());
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new WorkerException(HttpStatus.CONFLICT, "EXECUTION_ID_CONFLICT", "执行编号已用于其他工具请求");
            }
            return response(existing);
        }

        SandboxAllocation sandbox = sandboxes.findAllocation(sandboxId);
        if (tools.requiresRepository(request.getTool()) && request.getRepositoryId() == null) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, "REPOSITORY_REQUIRED", "该工具必须指定仓库编号");
        }
        ToolExecutionEntity entity = createEntity(sandboxId, request, argumentsJson, requestHash);
        executionMapper.insert(entity);
        entity.setStatus("RUNNING");
        entity.setStartedAt(utc(clock.instant()));
        executionMapper.updateById(entity);
        append(entity, "SYSTEM", "开始执行工具 " + request.getTool());
        try {
            Duration timeout = timeout(request.getTimeoutSeconds(), sandbox.getExecutionTimeout());
            ToolContext context = context(sandbox, request.getRepositoryId(), timeout);
            ToolResult result = tools.execute(request.getTool(), context, request.getArguments());
            result.getStandardOutput().forEach(line -> append(entity, "STDOUT", line));
            result.getStandardError().forEach(line -> append(entity, "STDERR", line));
            entity.setExitCode(result.getExitCode());
            entity.setResultJson(json(result.getResult()));
            entity.setStatus(result.getExitCode() == null || result.getExitCode() == 0 ? "SUCCEEDED" : "FAILED");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            entity.setStatus("TIMED_OUT");
            entity.setFailureReason("工具执行超时或被中断");
        } catch (RuntimeException exception) {
            entity.setStatus("FAILED");
            entity.setFailureReason(safeMessage(exception));
        } finally {
            entity.setFinishedAt(utc(clock.instant()));
            executionMapper.updateById(entity);
            append(entity, "SYSTEM", "工具执行结束，状态：" + entity.getStatus());
        }
        return response(entity);
    }

    /**
     * 查询工具执行的最终或当前状态。
     *
     * @param executionId 工具执行编号
     * @return 已持久化的工具执行信息
     * @throws WorkerException 执行记录不存在时抛出
     */
    public ToolExecutionResponse find(UUID executionId) {
        ToolExecutionEntity entity = executionMapper.selectById(executionId.toString());
        if (entity == null) {
            throw new WorkerException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "工具执行记录不存在");
        }
        return response(entity);
    }

    /**
     * 按执行内递增序号分页读取日志。
     *
     * @param executionId 工具执行编号
     * @param after 只返回序号大于该值的日志
     * @param limit 本次最大返回数量，服务端会限制在 1 到 1000 之间
     * @return 日志列表和下一次查询使用的游标
     */
    public ExecutionLogsResponse logs(UUID executionId, long after, int limit) {
        List<ToolExecutionLogEntity> rows = executionMapper.selectById(executionId.toString()) == null
                ? null : selectLogs(executionId, after, limit);
        if (rows == null) {
            throw new WorkerException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "工具执行记录不存在");
        }
        List<ExecutionLogEntryResponse> items = rows.stream()
                .map(row -> new ExecutionLogEntryResponse(row.getSequenceNo(), row.getStream(), row.getContent(),
                        row.getCreatedAt().toInstant(ZoneOffset.UTC)))
                .toList();
        long cursor = items.isEmpty() ? after : items.get(items.size() - 1).getSequence();
        return new ExecutionLogsResponse(items, cursor);
    }

    private List<ToolExecutionLogEntity> selectLogs(UUID executionId, long after, int limit) {
        return logMapper.selectAfter(executionId.toString(), after, Math.max(1, Math.min(limit, 1000)));
    }

    private ToolContext context(SandboxAllocation sandbox, UUID repositoryId, Duration timeout) {
        if (repositoryId == null) {
            return new ToolContext(sandbox, null, null, "/workspace", timeout);
        }
        return new ToolContext(sandbox, repositoryId, paths.resolveRepositoryLocal(sandbox, repositoryId),
                paths.resolveRepositoryContainer(sandbox, repositoryId), timeout);
    }

    private ToolExecutionEntity createEntity(UUID sandboxId, ToolExecutionRequest request, String argumentsJson,
            String requestHash) {
        ToolExecutionEntity entity = new ToolExecutionEntity();
        entity.setId(request.getExecutionId().toString());
        entity.setSandboxId(sandboxId.toString());
        entity.setRepositoryId(request.getRepositoryId() == null ? null : request.getRepositoryId().toString());
        entity.setToolName(request.getTool());
        entity.setRequestHash(requestHash);
        entity.setArgumentsJson(argumentsJson);
        entity.setStatus("QUEUED");
        entity.setCreatedAt(utc(clock.instant()));
        return entity;
    }

    private void append(ToolExecutionEntity entity, String stream, String content) {
        ToolExecutionLogEntity log = new ToolExecutionLogEntity();
        log.setExecutionId(entity.getId());
        log.setSequenceNo(logMapper.selectMaxSequence(entity.getId()) + 1);
        log.setStream(stream);
        log.setContent(content.length() <= 16000 ? content : content.substring(0, 16000));
        log.setCreatedAt(utc(clock.instant()));
        logMapper.insert(log);
    }

    private Duration timeout(Long seconds, Duration sandboxLimit) {
        Duration requested = seconds == null ? sandboxLimit : Duration.ofSeconds(seconds);
        Duration effective = requested.compareTo(sandboxLimit) <= 0 ? requested : sandboxLimit;
        return effective.compareTo(properties.getMaxExecutionTimeout()) <= 0
                ? effective : properties.getMaxExecutionTimeout();
    }

    private String hash(UUID sandboxId, ToolExecutionRequest request, String argumentsJson) {
        return sha256(sandboxId + "|" + request.getRepositoryId() + "|" + request.getTool() + "|" + argumentsJson);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("计算请求哈希失败", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, "TOOL_ARGUMENT_INVALID", "工具参数无法序列化");
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
                ? exception.getMessage() : "工具执行失败";
    }

    private ToolExecutionResponse response(ToolExecutionEntity entity) {
        return new ToolExecutionResponse(UUID.fromString(entity.getId()), UUID.fromString(entity.getSandboxId()),
                entity.getRepositoryId() == null ? null : UUID.fromString(entity.getRepositoryId()), entity.getToolName(),
                entity.getStatus(), entity.getExitCode(), map(entity.getResultJson()), entity.getFailureReason(),
                instant(entity.getCreatedAt()), instant(entity.getStartedAt()), instant(entity.getFinishedAt()));
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
