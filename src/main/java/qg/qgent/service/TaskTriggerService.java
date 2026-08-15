package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.dto.Mention;
import qg.qgent.dto.TaskCreateRequest;
import qg.qgent.dto.TaskResponse;
import qg.qgent.dto.TaskTriggerRequest;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.MessageMapper;
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
    private final TaskMapper taskMapper;
    private final TaskService taskService;
    private final ProjectAccessService access;
    private final ObjectMapper mapper;

    public TaskTriggerService(MessageMapper messageMapper, RequirementGroupMapper groupMapper,
                              RequirementGroupRepositoryMapper groupRepoMapper, TaskMapper taskMapper, TaskService taskService,
                              ProjectAccessService access, ObjectMapper mapper) {
        this.messageMapper = messageMapper;
        this.groupMapper = groupMapper;
        this.groupRepoMapper = groupRepoMapper;
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.access = access;
        this.mapper = mapper;
    }

    /**
     * 显式触发：从一条群消息创建 Task。
     * <p>
     * 消息必须属于该需求群，否则 404；请求缺省字段（requirement/repositoryIds/baseRef）
     * 由服务端从消息文本或群信息提取。
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
        access.requireProjectMember(projectId, actor);
        MessageEntity message = requireMessageInGroup(groupId, messageId);
        RequirementGroupEntity group = requireActiveRequirementGroup(projectId, groupId);
        TaskCreateRequest request = assembleRequest(actor, projectId, group, message, body.getTitle(),
                body.getRequirement(), body.getRepositoryIds(), body.getBaseRef());
        return taskService.create(projectId, actor, request);
    }

    /**
     * 自动触发：检测消息 mentions 中是否存在 {@code type=AGENT}，存在则从该消息创建 Task。
     * <p>
     * 幂等防重：同一 {@code triggerMessageId} 已有关联 Task 时跳过；群未绑仓库时跳过并记录
     * warn（@agent 消息不应因缺仓库而阻塞聊天，不抛错）。
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
        List<UUID> repositoryIds = groupRepoMapper.selectRepositoryIds(groupId);
        if (repositoryIds.isEmpty()) {
            log.warn("task auto-trigger skipped: requirement group {} has no bound repositories", groupId);
            return null;
        }
        String title = messageText(message.getContent());
        if (title == null || title.isBlank()) {
            title = group.getName();
        }
        TaskCreateRequest request = assembleRequest(actor, projectId, group, message, title, null, repositoryIds, null);
        return taskService.create(projectId, actor, request);
    }

    private TaskCreateRequest assembleRequest(UUID actor, UUID projectId, RequirementGroupEntity group,
                                              MessageEntity message, String title, String requirement, List<UUID> repositoryIds, String baseRef) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setRequirementGroupId(group.getId());
        request.setTriggerMessageId(message.getId());
        request.setTitle(truncate(title != null && !title.isBlank() ? title.trim() : group.getName(), MAX_TITLE));
        request.setRequirement(requirement != null && !requirement.isBlank() ? requirement.trim()
                : defaultRequirement(group, message));
        request.setRepositoryIds(repositoryIds == null || repositoryIds.isEmpty()
                ? groupRepoMapper.selectRepositoryIds(group.getId()) : repositoryIds);
        request.setBaseRef(baseRef);
        return request;
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
}
