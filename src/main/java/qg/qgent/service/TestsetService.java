package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import qg.qgent.dto.TestsetResponse;
import qg.qgent.entity.TestsetEntity;
import qg.qgent.mapper.TestsetMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Testset 配置查询服务（契约 §10）。
 * <p>
 * 本期只读查询（列表）；创建/修改/启停/删除由 Testset 管理功能后续提供。
 * 列表按项目隔离，支持按仓库过滤，仅项目成员可访问。
 */
@Service
public class TestsetService {
    private final TestsetMapper testsetMapper;
    private final ProjectAccessService access;

    public TestsetService(TestsetMapper testsetMapper, ProjectAccessService access) {
        this.testsetMapper = testsetMapper;
        this.access = access;
    }

    /**
     * 查询项目内 Testset 配置列表。
     *
     * @param projectId    项目 ID
     * @param actor        当前用户 ID
     * @param repositoryId 可选的项目仓库绑定 ID 过滤；为空返回项目全部
     * @return Testset 配置视图列表
     */
    public List<TestsetResponse> list(UUID projectId, UUID actor, UUID repositoryId) {
        access.requireProjectMember(projectId, actor);
        return testsetMapper.selectList(Wrappers.<TestsetEntity>lambdaQuery()
                .eq(TestsetEntity::getProjectId, projectId)
                .eq(repositoryId != null, TestsetEntity::getProjectRepositoryId, repositoryId)
                .orderByDesc(TestsetEntity::getCreatedAt))
                .stream().map(this::toResponse).toList();
    }

    private TestsetResponse toResponse(TestsetEntity entity) {
        return new TestsetResponse(id(entity.getId()), entity.getName(),
                id(entity.getProjectRepositoryId()), entity.getStatus(),
                "ENABLED".equals(entity.getStatus()), entity.getDefinition(),
                id(entity.getCreatedBy()), iso(entity.getCreatedAt()), iso(entity.getUpdatedAt()));
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toInstant().toString();
    }
}
