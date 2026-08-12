package qg.qgent.sandboxworker.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import qg.qgent.sandboxworker.runtime.SandboxAllocation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/** 工具执行时使用的受控上下文。 */
@Data
@AllArgsConstructor
public class ToolContext {
    private SandboxAllocation sandbox;
    private UUID repositoryId;
    private Path localRepository;
    private String containerRepository;
    private Duration timeout;
}
