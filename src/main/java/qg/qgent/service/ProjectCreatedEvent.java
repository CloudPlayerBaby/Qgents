package qg.qgent.service;

import java.util.UUID;

/**
 * 项目创建完成事件。
 * <p>
 * 由项目域（后端2 的项目创建服务）在项目及其 project_members 落库后发布，
 * {@link GroupService} 监听后自动创建唯一的 PROJECT_MAIN 群。发布方只需：
 * <pre>{@code
 * applicationEventPublisher.publishEvent(new ProjectCreatedEvent(projectId, projectName, creatorUserId));
 * }</pre>
 *
 * @param projectId     新项目 ID。
 * @param projectName   新项目名称，用作主群标题。
 * @param creatorUserId 项目创建者用户 ID（会自动成为 PROJECT_ADMIN）。
 */
public record ProjectCreatedEvent(UUID projectId, String projectName, UUID creatorUserId) {

}
