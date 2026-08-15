package qg.qgent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.dto.ProjectSettings;
import qg.qgent.dto.ProjectSettingsUpdateRequest;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.mapper.ProjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 项目设置（需求群规则开关）读写（成员B 后端接口补充清单 §二）。
 * <p>
 * GET 项目成员可读（默认值兜底）；PATCH 仅 Project Admin（部分更新，未传字段不覆盖）。
 * 设置持久化在 projects.settings（JSON 列）。
 */
@Service
public class ProjectSettingsService {

    private final ProjectMapper projectMapper;
    private final ProjectAccessService access;

    public ProjectSettingsService(ProjectMapper projectMapper, ProjectAccessService access) {
        this.projectMapper = projectMapper;
        this.access = access;
    }

    /**
     * 读取项目设置（项目成员）。
     */
    public ProjectSettings get(UUID projectId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        ProjectEntity project = require(projectId);
        return toSettings(project.getSettings());
    }

    /**
     * 更新项目设置（Project Admin，部分更新）。
     */
    @Transactional
    public ProjectSettings update(UUID projectId, UUID actor, ProjectSettingsUpdateRequest request) {
        access.requireProjectAdmin(projectId, actor);
        ProjectEntity project = require(projectId);
        Map<String, Object> settings = new LinkedHashMap<>(normalize(project.getSettings()));
        if (request.getAllowCreateGroup() != null) {
            settings.put("allowCreateGroup", request.getAllowCreateGroup());
        }
        if (request.getAutoArchiveGroup() != null) {
            settings.put("autoArchiveGroup", request.getAutoArchiveGroup());
        }
        if (request.getAllowAgentTrigger() != null) {
            settings.put("allowAgentTrigger", request.getAllowAgentTrigger());
        }
        if (request.getAutoJoinAllGroups() != null) {
            settings.put("autoJoinAllGroups", request.getAutoJoinAllGroups());
        }
        project.setSettings(settings);
        projectMapper.updateById(project);
        return toSettings(settings);
    }

    private ProjectEntity require(UUID projectId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "PROJECT_NOT_FOUND", "项目不存在或不可见");
        }
        return project;
    }

    /**
     * 合并存储 Map 与默认值：缺失键使用默认值。
     */
    private Map<String, Object> normalize(Map<String, Object> stored) {
        ProjectSettings defaults = new ProjectSettings();
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("allowCreateGroup", stored == null || !stored.containsKey("allowCreateGroup")
                ? defaults.isAllowCreateGroup() : stored.get("allowCreateGroup"));
        merged.put("autoArchiveGroup", stored == null || !stored.containsKey("autoArchiveGroup")
                ? defaults.isAutoArchiveGroup() : stored.get("autoArchiveGroup"));
        merged.put("allowAgentTrigger", stored == null || !stored.containsKey("allowAgentTrigger")
                ? defaults.isAllowAgentTrigger() : stored.get("allowAgentTrigger"));
        merged.put("autoJoinAllGroups", stored == null || !stored.containsKey("autoJoinAllGroups")
                ? defaults.isAutoJoinAllGroups() : stored.get("autoJoinAllGroups"));
        return merged;
    }

    private ProjectSettings toSettings(Map<String, Object> stored) {
        Map<String, Object> merged = normalize(stored);
        return new ProjectSettings(
                truthy(merged.get("allowCreateGroup")),
                truthy(merged.get("autoArchiveGroup")),
                truthy(merged.get("allowAgentTrigger")),
                truthy(merged.get("autoJoinAllGroups")));
    }

    private boolean truthy(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }
}
