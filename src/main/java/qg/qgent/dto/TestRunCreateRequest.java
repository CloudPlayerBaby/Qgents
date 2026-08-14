package qg.qgent.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 发起受控测试运行请求。
 * 必须提供 repositoryId，且 taskId 与 ref 二选一；testsetIds 必须属于该仓库且为 ENABLED。
 */
@Data
public class TestRunCreateRequest {
    /** 项目仓库绑定ID。 */
    @NotNull
    private UUID repositoryId;
    /** 关联 Task ID，与 ref 二选一。 */
    private UUID taskId;
    /** 目标提交或分支引用，与 taskId 二选一。 */
    @Size(max = 512)
    private String ref;
    /** 启用测试集ID数组。 */
    @NotEmpty
    @Size(max = 32)
    private List<UUID> testsetIds;
}
