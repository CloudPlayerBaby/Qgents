package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.dto.Mention;
import qg.qgent.dto.TaskCreateRequest;
import qg.qgent.dto.TaskResponse;
import qg.qgent.dto.TaskTriggerRequest;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.RequirementGroupRepositoryMapper;
import qg.qgent.mapper.TaskMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 从群消息触发 Task 的转换服务（点7：聊天消息到 Agent Task 的转换）。
 * <p>
 * 后端4 转换职责：把一条需求群消息组装为 {@link TaskCreateRequest} 并调
 * {@link TaskService#create} 建 Task。两种入口：
 * <ul>
 *   <li>显式触发：{@link #trigger}，由前端/客户端在消息详情上「一键触发任务」；</li>
 *   <li>自动触发：{@link #triggerFromMention}，在群消息 {@code @agent} 时自动建 Task。</li>
 * </ul>
 * Task 创建后编排由后端1 的 Orchestrator 自动触发，本服务不调用编排。
 */
@Service
public class TaskTriggerService {
    private static final Logger log = LoggerFactory.getLogger(TaskTriggerService.class);
    private static final int MAX_TITLE = 255;

    private final MessageMapper messageMapper;
    private final RequirementGroupMapper groupMapper;
    private final RequirementGroupRepositoryMapper groupRepoMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final TaskMapper taskMapper;
    private final DiffMapper diffMapper;
    private final TaskService taskService;
    private final MessageService messageService;
    private final GroupService groupService;
    private final ProjectAccessService access;
    private final ObjectMapper mapper;
    /** 引用 Diff 续作的分支状态门禁；轻量领域单测可不注入。 */
    private WorkBranchDevelopmentGuard developmentGuard;

    public TaskTriggerService(MessageMapper messageMapper, RequirementGroupMapper groupMapper,
                              RequirementGroupRepositoryMapper groupRepoMapper, TaskMapper taskMapper,
                              ProjectRepositoryMapper projectRepositoryMapper, DiffMapper diffMapper,
                              TaskService taskService, MessageService messageService,
                              GroupService groupService,
                              ProjectAccessService access, ObjectMapper mapper) {
        this.messageMapper = messageMapper;
        this.groupMapper = groupMapper;
        this.groupRepoMapper = groupRepoMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.taskMapper = taskMapper;
        this.diffMapper = diffMapper;
        this.taskService = taskService;
        this.messageService = messageService;
        this.groupService = groupService;
        this.access = access;
        this.mapper = mapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDevelopmentGuard(WorkBranchDevelopmentGuard developmentGuard) {
        this.developmentGuard = developmentGuard;
    }

    /**
     * 显式触发：从一条群消息创建 Task。
     * <p>
     * 消息必须属于该需求群，否则 404；请求缺省字段（title/requirement/repositoryIds/baseRef）
     * 由服务端从消息文本或项目绑定仓库提取。新建 Workspace 且请求未传
     * {@code repositoryIds} 时，服务端使用项目 ACTIVE 仓库作为 Planner 候选范围。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   需求群 ID
     * @param messageId 触发消息 ID
     * @param body      触发请求
     * @return 创建的 Task 视图
     */
    @Transactional
    public TaskResponse trigger(UUID actor, UUID projectId, UUID groupId, UUID messageId, TaskTriggerRequest body) {
        // 群成员可见性（契约 2026-08-17 严格收紧）：触发任务者必须是群成员
        groupService.requireGroupMember(projectId, groupId, actor);
        MessageEntity message = requireMessageInGroup(groupId, messageId);
        RequirementGroupEntity group = requireActiveRequirementGroup(projectId, groupId);
        // 幂等：同一触发消息只建一次 Task（引用 DIFF 续作尤其要防重复点击创建多个续作 Task）
        TaskResponse existing = taskService.findByTriggerMessage(projectId, message.getId(), actor);
        if (existing != null) {
            return existing;
        }
        ContinuationRef continuation = resolveQuotedDiffContinuation(projectId, groupId, message);
        TaskCreateRequest request = assembleRequest(group, message, body.getTitle(),
                body.getRequirement(), continuation, body.getRepositoryIds(), body.getBaseRef(),
                body.getBaseRefs());
        // 需求群共享项目仓库范围：客户端没有指定仓库时，使用项目当前 ACTIVE 仓库，
        // 再由 Planner 物化阶段收敛实际涉及的仓库。
        if (continuation == null && (request.getRepositoryIds() == null || request.getRepositoryIds().isEmpty())) {
            List<UUID> projectRepositories = projectRepositoryMapper.selectList(
                    Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                            .eq(ProjectRepositoryEntity::getProjectId, projectId)
                            .eq(ProjectRepositoryEntity::getStatus, "ACTIVE"))
                    .stream().map(ProjectRepositoryEntity::getId).toList();
            if (projectRepositories.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PROJECT_NO_ACTIVE_REPOSITORIES",
                        "项目当前没有可用仓库，请先绑定至少一个有效仓库");
            }
            request.setRepositoryIds(projectRepositories);
        }
        request.setDeliveryMode(body.getDeliveryMode());
        return createIdempotent(projectId, actor, message, request);
    }

    /**
     * 自动触发：检测消息 mentions 中是否存在 {@code type=AGENT}，存在则从该消息创建 Task。
     * <p>
     * 幂等防重：同一 {@code triggerMessageId} 已有关联 Task 时跳过。新 Workspace 的候选仓库
     * 统一取项目当前 ACTIVE 仓库，需求群不再拥有独立的仓库范围；Planner 后续再收敛实际写入范围，
     * 不要求前端把项目仓库清单拼进请求。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   需求群 ID
     * @param message   已落库的触发消息
     * @param mentions  消息提及列表
     * @return 创建的 Task 视图；跳过时返回 null
     */
    @Transactional
    public TaskResponse triggerFromMention(UUID actor, UUID projectId, UUID groupId, MessageEntity message,
                                           List<Mention> mentions) {
        if (mentions == null || mentions.stream().noneMatch(m -> "AGENT".equals(m.getType()))) {
            return null;
        }
        if (message == null || !groupId.equals(message.getRequirementGroupId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_IN_GROUP", "消息不属于当前需求群");
        }
        // 幂等：同一条消息只建一次 Task
        boolean alreadyTriggered = taskMapper.selectCount(Wrappers.<TaskEntity>lambdaQuery()
                .eq(TaskEntity::getTriggerMessageId, message.getId())) > 0;
        if (alreadyTriggered) {
            return null;
        }
        RequirementGroupEntity group = requireActiveRequirementGroup(projectId, groupId);
        ContinuationRef continuation = resolveQuotedDiffContinuation(projectId, groupId, message);
        List<UUID> projectRepositories = continuation == null ? activeProjectRepositoryIds(projectId) : null;
        if (continuation == null && projectRepositories.isEmpty()) {
            log.warn("task auto-trigger skipped: project {} has no active repositories", projectId);
            return null;
        }
        String title = messageText(message.getContent());
        if (title == null || title.isBlank()) {
            title = group.getName();
        }
        TaskCreateRequest request = assembleRequest(group, message, title, null, continuation, projectRepositories, null,
                null);
        return createIdempotent(projectId, actor, message, request);
    }

    /**
     * 建任务 + 并发幂等兜底：唯一约束（uk_task_trigger_message）冲突说明同消息已被并发请求建过 Task，
     * 捕获后返回已有任务而非再次创建，避免 @agent 自动触发与手动触发并发时建出两个 Task。
     */
    private TaskResponse createIdempotent(UUID projectId, UUID actor, MessageEntity message, TaskCreateRequest request) {
        try {
            return taskService.create(projectId, actor, request);
        } catch (DuplicateKeyException e) {
            TaskResponse existing = taskService.findByTriggerMessage(projectId, message.getId(), actor);
            if (existing != null) {
                log.warn("task trigger idempotency: duplicate creation for message {} returned existing task {}",
                        message.getId(), existing.getId());
                return existing;
            }
            throw e;
        }
    }

    private TaskCreateRequest assembleRequest(RequirementGroupEntity group,
                                              MessageEntity message, String title, String requirement,
                                              ContinuationRef continuation, List<UUID> repositoryIds, String baseRef,
                                              Map<UUID, String> baseRefs) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setRequirementGroupId(group.getId());
        request.setTriggerMessageId(message.getId());
        request.setTitle(truncate(title != null && !title.isBlank() ? title.trim() : group.getName(), MAX_TITLE));
        request.setRequirement(requirement != null && !requirement.isBlank() ? requirement.trim()
                : defaultRequirement(group, message));
        if (continuation != null) {
            // 引用 DIFF 续作：复用源 Task 的 Workspace，仓库范围由 Workspace 继承，不接受客户端仓库参数。
            request.setWorkspaceId(continuation.workspaceId());
            request.setContinuationOfTaskId(continuation.taskId());
            request.setRepositoryIds(null);
        } else {
            // 新 Workspace 的仓库范围由调用方从项目 ACTIVE 仓库解析；不要再回读需求群旧绑定，
            // 否则历史群绑定会把不属于当前项目候选范围的仓库重新带入任务。
            request.setRepositoryIds(repositoryIds);
        }
        request.setBaseRef(baseRef);
        request.setBaseRefs(baseRefs);
        return request;
    }

    private List<UUID> activeProjectRepositoryIds(UUID projectId) {
        return projectRepositoryMapper.selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                        .eq(ProjectRepositoryEntity::getProjectId, projectId)
                        .eq(ProjectRepositoryEntity::getStatus, "ACTIVE"))
                .stream().map(ProjectRepositoryEntity::getId).toList();
    }

    /**
     * 缺省需求描述：触发消息文本，否则群描述，再否则群标题。
     */
    private String defaultRequirement(RequirementGroupEntity group, MessageEntity message) {
        String text = messageText(message.getContent());
        if (text != null && !text.isBlank()) {
            return text;
        }
        return group.getDescription() != null && !group.getDescription().isBlank() ? group.getDescription()
                : group.getName();
    }

    /**
     * 解析当前消息的引用续作：仅当消息直接回复 {@code message_type=DIFF} 的消息时，从该 DIFF
     * 消息的结构化 {@code content.diffId} 解析 Diff 及其源 Task/Workspace，返回续作引用。
     * 未引用或引用非 DIFF 消息时返回 null（新建 Workspace）；DIFF 内容损坏、Diff 不存在或
     * 归属不一致时返回稳定的 422 错误，不静默降级为新建 Workspace。
     */
    private ContinuationRef resolveQuotedDiffContinuation(UUID projectId, UUID groupId, MessageEntity message) {
        UUID replyToId = message.getReplyToMessageId();
        if (replyToId == null) {
            return null;
        }
        MessageEntity parent = messageMapper.selectById(replyToId);
        if (parent == null || !groupId.equals(parent.getRequirementGroupId())
                || !"DIFF".equals(parent.getMessageType())) {
            return null;
        }
        UUID diffId = parseDiffId(parent.getContent());
        if (diffId == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTED_DIFF_INVALID",
                    "被引用的 DIFF 消息缺少有效的 diffId");
        }
        DiffEntity diff = diffMapper.selectById(diffId);
        if (diff == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTED_DIFF_NOT_ACCESSIBLE",
                    "被引用的 Diff 不存在");
        }
        if (!projectId.equals(diff.getProjectId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTED_DIFF_NOT_ACCESSIBLE",
                    "被引用的 Diff 不属于当前项目");
        }
        TaskEntity sourceTask = taskMapper.selectById(diff.getTaskId());
        if (sourceTask == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTED_DIFF_NOT_ACCESSIBLE",
                    "被引用 Diff 的源 Task 不存在");
        }
        if (!projectId.equals(sourceTask.getProjectId()) || !groupId.equals(sourceTask.getRequirementGroupId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTED_DIFF_NOT_ACCESSIBLE",
                    "被引用 Diff 的源 Task 不属于当前项目或需求群");
        }
        if (!diff.getWorkspaceId().equals(sourceTask.getWorkspaceId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTED_DIFF_INVALID",
                    "被引用 Diff 与源 Task 的 Workspace 不一致");
        }
        if (developmentGuard != null) {
            developmentGuard.requireQuotedDiffContinuationAllowed(projectId, diff.getWorkspaceId());
        }
        return new ContinuationRef(sourceTask.getId(), diff.getWorkspaceId());
    }

    /**
     * 从 DIFF 消息的结构化 content 提取 diffId；内容非法或缺少 diffId 时返回 null。
     */
    private UUID parseDiffId(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> map = mapper.readValue(contentJson, Map.class);
            Object diffId = map.get("diffId");
            if (diffId == null || diffId.toString().isBlank()) {
                return null;
            }
            return UUID.fromString(diffId.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private MessageEntity requireMessageInGroup(UUID groupId, UUID messageId) {
        MessageEntity message = messageMapper.selectById(messageId);
        if (message == null || !groupId.equals(message.getRequirementGroupId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_IN_GROUP", "消息不存在或不属于当前需求群");
        }
        return message;
    }

    private RequirementGroupEntity requireActiveRequirementGroup(UUID projectId, UUID groupId) {
        RequirementGroupEntity group = groupMapper.selectById(groupId);
        if (group == null || !projectId.equals(group.getProjectId()) || !"REQUIREMENT".equals(group.getGroupType())
                || !"ACTIVE".equals(group.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACTIVE_REQUIREMENT_GROUP_REQUIRED",
                    "任务必须来自当前项目中有效的需求群");
        }
        return group;
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 从消息 content JSON 提取可读文本（TEXT 取 $.text，其余类型返回原始 JSON 截断）。
     */
    private String messageText(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        try {
            Map<?, ?> map = mapper.readValue(contentJson, Map.class);
            Object text = map.get("text");
            return text == null ? contentJson : text.toString();
        } catch (Exception e) {
            return contentJson;
        }
    }

    /**
     * 引用 DIFF 的续作引用：源 Task 与其 Workspace（后续 Task 复用该 Workspace）。
     */
    private record ContinuationRef(UUID taskId, UUID workspaceId) {
    }
}
