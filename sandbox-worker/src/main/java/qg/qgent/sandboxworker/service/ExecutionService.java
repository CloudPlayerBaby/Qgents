package qg.qgent.sandboxworker.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandboxworker.api.CreateExecutionRequest;
import qg.qgent.sandboxworker.api.ExecutionLogEntryResponse;
import qg.qgent.sandboxworker.api.ExecutionLogsResponse;
import qg.qgent.sandboxworker.api.ExecutionResponse;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;
import qg.qgent.sandboxworker.runtime.SandboxAllocation;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 负责异步命令、取消、超时结果和递增日志。 */
@Service
@RequiredArgsConstructor
public class ExecutionService {
    private static final int MAX_LOG_LIMIT = 1000;

    private final SandboxService sandboxes;
    private final CommandExecutor commandExecutor;
    private final SandboxWorkerProperties properties;
    private final ExecutorService sandboxExecutionPool;
    private final ScheduledExecutorService sandboxTimeoutScheduler;
    private final Clock clock;
    private final ConcurrentMap<UUID, ExecutionRecord> executions = new ConcurrentHashMap<>();

    /**
     * 为 READY 沙箱提交一条异步命令。
     * 命令使用参数数组表达，不接受 shell 字符串；同一 executionId 的重放必须属于同一沙箱和同一命令。
     */
    public ExecutionResponse create(UUID sandboxId, CreateExecutionRequest request) {
        // 先构造这一次的执行记录
        ExecutionRecord candidate = new ExecutionRecord(request.getExecutionId(), sandboxId,
                List.copyOf(request.getCommand()), clock.instant());
        // 看一下这条命令的ID是否已经存在
        ExecutionRecord record = executions.get(candidate.getId());
        if (record != null) {
            requireSameRequest(record, candidate);
            return response(record);
        }

        // 确保这个沙箱 READY 了
        SandboxAllocation sandbox = sandboxes.requireReady(sandboxId);
        // 加锁
        synchronized (sandbox) {
            // 再确认
            record = executions.get(candidate.getId());
            if (record != null) {
                requireSameRequest(record, candidate);
                return response(record);
            }
            if (!"READY".equals(sandbox.getStatus())) {
                throw new WorkerException(HttpStatus.CONFLICT, "SANDBOX_NOT_READY", "沙箱当前不可执行命令");
            }
            executions.put(candidate.getId(), candidate);
            // 标记成正在执行
            sandboxes.markBusy(sandboxId);
        }
        // 计算超时时间
        Duration timeout = effectiveTimeout(request.getTimeoutSeconds(), sandbox.getExecutionTimeout());
        // 往线程池提交执行任务
        candidate.setFuture(sandboxExecutionPool.submit(() -> run(candidate, sandbox, timeout)));
        // 提交超时任务，timeout 之后执行去查看
        candidate.setTimeoutFuture(sandboxTimeoutScheduler.schedule(() -> timeout(candidate), timeout.toMillis(),
                TimeUnit.MILLISECONDS));
        // 返回执行记录
        return response(candidate);
    }

    // 找到执行记录
    public ExecutionResponse find(UUID executionId) {
        return response(require(executionId));
    }

    /**
     * 取消排队中或运行中的命令。
     * 重复取消已经结束的执行不会改变其最终结果。
     */
    public ExecutionResponse cancel(UUID executionId) {
        // 找到执行记录
        ExecutionRecord record = require(executionId);
        // 加锁
        synchronized (record) {
            // 已经完成就返回
            if (isFinished(record.getStatus())) {
                return response(record);
            }
            // 否则设置一下状态为已取消
            record.setStatus("CANCELLED");
            record.setFinishedAt(clock.instant());
            record.append("SYSTEM", "执行已取消", clock.instant());
            // 取消执行任务和超时任务
            if (record.getFuture() != null) {
                record.getFuture().cancel(true);
            }
            if (record.getTimeoutFuture() != null) {
                record.getTimeoutFuture().cancel(false);
            }
            // 如果还没有开始，那么就需要手动标记成可执行
            if (record.getStartedAt() == null) {
                sandboxes.markReady(record.getSandboxId());
            }
            return response(record);
        }
    }

    // 找到执行日志
    public ExecutionLogsResponse logs(UUID executionId, long after, int limit) {
        // 找到对应的执行记录
        ExecutionRecord record = require(executionId);
        // 确认大小范围
        int size = Math.max(1, Math.min(limit, MAX_LOG_LIMIT));
        // 找到日志并过滤
        List<ExecutionLogEntryResponse> items = record
                .getLogs()
                .stream()
                .filter(log -> log.getSequence() > after)
                .limit(size)
                .toList();
        // 计算下一个游标
        long nextCursor = items.isEmpty() ? after : items.get(items.size() - 1).getSequence();
        return new ExecutionLogsResponse(items, nextCursor);
    }

    // 根据沙箱ID取消所有活动执行
    public void cancelBySandbox(UUID sandboxId) {
        executions.values().stream()
                .filter(record -> record.getSandboxId().equals(sandboxId) && !isFinished(record.getStatus()))
                .forEach(record -> cancel(record.getId()));
    }

    // 真正去跑一个任务
    private void run(ExecutionRecord record, SandboxAllocation sandbox, Duration timeout) {
        // 加锁
        synchronized (record) {
            // 已经取消就返回
            if ("CANCELLED".equals(record.getStatus())) {
                return;
            }
            // 开始执行
            record.setStatus("RUNNING");
            record.setStartedAt(clock.instant());
            record.append("SYSTEM", "开始执行命令", clock.instant());
        }
        // 开始执行
        try {
            CommandExecutionResult result = commandExecutor.execute(sandbox, "/workspace", record.getCommand(),
                    timeout);
            synchronized (record) {
                if ("CANCELLED".equals(record.getStatus())) {
                    return;
                }
                result.getStandardOutput().forEach(line -> record.append("STDOUT", line, clock.instant()));
                result.getStandardError().forEach(line -> record.append("STDERR", line, clock.instant()));
                record.setExitCode(result.getExitCode());
                record.setStatus(result.getExitCode() == 0 ? "SUCCEEDED" : "FAILED");
                record.setFinishedAt(clock.instant());
                cancelTimeout(record);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            synchronized (record) {
                if (!"CANCELLED".equals(record.getStatus())) {
                    record.setStatus("TIMED_OUT");
                    record.setFailureReason("执行超时或被工作节点中断");
                    record.setFinishedAt(clock.instant());
                    record.append("SYSTEM", record.getFailureReason(), clock.instant());
                }
            }
        } catch (RuntimeException exception) {
            synchronized (record) {
                record.setStatus("FAILED");
                record.setFailureReason("底层执行器执行失败");
                record.setFinishedAt(clock.instant());
                record.append("SYSTEM", record.getFailureReason(), clock.instant());
            }
        } finally {
            // 不管怎么样一定要把沙箱的状态改成 READY
            sandboxes.markReady(record.getSandboxId());
        }
    }

    private void timeout(ExecutionRecord record) {
        synchronized (record) {
            if (isFinished(record.getStatus())) {
                return;
            }
            record.setStatus("TIMED_OUT");
            record.setFailureReason("执行超过允许时限");
            record.setFinishedAt(clock.instant());
            record.append("SYSTEM", record.getFailureReason(), clock.instant());
            if (record.getFuture() != null) {
                record.getFuture().cancel(true);
            }
        }
        if (record.getStartedAt() == null) {
            sandboxes.markReady(record.getSandboxId());
        }
    }

    private void cancelTimeout(ExecutionRecord record) {
        if (record.getTimeoutFuture() != null) {
            record.getTimeoutFuture().cancel(false);
        }
    }

    private Duration effectiveTimeout(Long requestedSeconds, Duration sandboxLimit) {
        Duration requested = requestedSeconds == null ? sandboxLimit : Duration.ofSeconds(requestedSeconds);
        Duration workerLimit = properties.getMaxExecutionTimeout();
        Duration effective = requested.compareTo(sandboxLimit) <= 0 ? requested : sandboxLimit;
        return effective.compareTo(workerLimit) <= 0 ? effective : workerLimit;
    }

    private ExecutionRecord require(UUID executionId) {
        ExecutionRecord record = executions.get(executionId);
        if (record == null) {
            throw new WorkerException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "执行记录不存在");
        }
        return record;
    }

    private void requireSameRequest(ExecutionRecord existing, ExecutionRecord candidate) {
        if (!existing.getSandboxId().equals(candidate.getSandboxId())
                || !existing.getCommand().equals(candidate.getCommand())) {
            throw new WorkerException(HttpStatus.CONFLICT, "EXECUTION_ID_CONFLICT", "执行编号已用于其他请求");
        }
    }

    private boolean isFinished(String status) {
        return List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(status);
    }

    private ExecutionResponse response(ExecutionRecord record) {
        return new ExecutionResponse(record.getId(), record.getSandboxId(), record.getStatus(), record.getExitCode(),
                record.getFailureReason(), record.getCreatedAt(), record.getStartedAt(), record.getFinishedAt());
    }
}
