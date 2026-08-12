package qg.qgent.sandboxworker.service;

import lombok.Data;
import qg.qgent.sandboxworker.api.ExecutionLogEntryResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工作节点内部维护的执行状态
 */
@Data
class ExecutionRecord {
    // ID
    private final UUID id;

    // 沙箱ID
    private final UUID sandboxId;

    // 命令内容
    private final List<String> command;

    // 创建时间
    private final Instant createdAt;

    // 日志序列号
    private final AtomicLong logSequence = new AtomicLong();

    // 执行日志
    private final List<ExecutionLogEntryResponse> logs = new CopyOnWriteArrayList<>();

    // 执行状态
    private volatile String status = "QUEUED";

    // 退出码
    private volatile Integer exitCode;

    // 失败原因
    private volatile String failureReason;

    // 开始时间
    private volatile Instant startedAt;

    // 结束时间
    private volatile Instant finishedAt;

    // 执行任务
    private volatile Future<?> future;

    // 超时任务
    private volatile ScheduledFuture<?> timeoutFuture;

    void append(String stream, String content, Instant timestamp) {
        logs.add(new ExecutionLogEntryResponse(logSequence.incrementAndGet(), stream, content, timestamp));
    }
}
