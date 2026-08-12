package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 列出仓库内指定目录的直接子项。 */
@Component
@RequiredArgsConstructor
public class FileListTool implements SandboxTool {
    private final RepositoryFileResolver files;

    @Override
    public String name() {
        return "file.list";
    }

    @Override
    public boolean requiresRepository() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String relativePath = ToolArguments.optionalString(arguments, "path", ".", 1024);
        Path directory = files.resolveExisting(context.getLocalRepository(), relativePath);
        try (var stream = Files.list(directory)) {
            List<Map<String, Object>> items = stream.limit(1000)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> Map.<String, Object>of("name", path.getFileName().toString(),
                            "directory", Files.isDirectory(path)))
                    .toList();
            return ToolResult.value(Map.of("path", relativePath, "items", items));
        } catch (Exception exception) {
            throw new IllegalStateException("列出目录失败", exception);
        }
    }
}
