package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import qg.qgent.entity.EventEntity;
import qg.qgent.entity.NotificationEventEntity;
import qg.qgent.entity.TeamEventEntity;
import qg.qgent.mapper.EventMapper;
import qg.qgent.mapper.GroupMemberMapper;
import qg.qgent.mapper.NotificationEventMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.TeamEventMapper;
import qg.qgent.mapper.TeamMemberMapper;
import qg.qgent.mapper.UserMapper;
import qg.qgent.mapper.TeamMapper;
import qg.qgent.service.event.DeliveryStartedDomainEvent;
import qg.qgent.service.event.MrFirstPreflightRequestedDomainEvent;
import qg.qgent.service.event.PreflightCqApprovedDomainEvent;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 事件发布与过期清理的事务边界测试。 */
class EventServiceTest {

    @Test
    void publishLocksProjectThenUsesConcurrentSafeNextSequence() {
        EventMapper events = mock(EventMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        UUID projectId = UUID.randomUUID();
        when(events.nextSequence(projectId)).thenReturn(9L);
        EventService service = service(events, projects);

        service.publish(projectId, null, "task-run.updated", "run-1", Map.of("status", "RUNNING"));

        // 发布前先持有项目行锁，串行化同项目事件写入，避免并发 MAX+1 撞唯一键 500
        verify(projects).selectByIdForUpdate(projectId);
        verify(events, never()).maxSequence(any());
        verify(events).nextSequence(projectId);
        org.mockito.ArgumentCaptor<EventEntity> captured = org.mockito.ArgumentCaptor.forClass(EventEntity.class);
        verify(events).insert(captured.capture());
        EventEntity event = captured.getValue();
        assertEquals(9L, event.getSequenceNo());
        assertEquals(projectId, event.getProjectId());
        assertEquals("task-run.updated", event.getEventType());
        assertNotNull(event.getCreatedAt());
        // 发布路径只能写入事件；过期清理由独立定时任务执行，不能参与调用方业务事务。
        verifyNoMoreInteractions(events);
    }

    @Test
    void purgeExpiredUsesDedicatedMaintenanceQuery() {
        EventMapper events = mock(EventMapper.class);
        when(events.deleteExpired(any())).thenReturn(3);
        EventService service = service(events, mock(ProjectMapper.class));

        service.purgeExpired();

        verify(events).deleteExpired(any());
        verifyNoMoreInteractions(events);
    }

    @Test
    void deliveryStartedPublishesTypedDomainEventWithThePersistedBatchId() {
        EventMapper events = mock(EventMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(events.nextSequence(projectId)).thenReturn(1L);
        EventService service = service(events, projects, publisher);

        service.publish(projectId, null, "delivery.started", taskId.toString(), Map.of(
                "taskId", taskId, "reviewBatchId", batchId, "operationId", "operation-1"));

        org.mockito.ArgumentCaptor<Object> captured = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captured.capture());
        DeliveryStartedDomainEvent event = (DeliveryStartedDomainEvent) captured.getValue();
        assertEquals(projectId, event.projectId());
        assertEquals(taskId, event.taskId());
        assertEquals(batchId, event.reviewBatchId());
        assertEquals("operation-1", event.operationId());
    }

    @Test
    void notificationSequenceIsAllocatedWhileRecipientRowIsLocked() {
        NotificationEventMapper notifications = mock(NotificationEventMapper.class);
        UserMapper users = mock(UserMapper.class);
        UUID userId = UUID.randomUUID();
        when(notifications.maxSequence(userId)).thenReturn(4L);
        EventService service = fullService(mock(EventMapper.class), notifications, mock(TeamEventMapper.class),
                users, mock(TeamMapper.class));

        service.publishNotification(userId, UUID.randomUUID(), "TASK_COMPLETED", Map.of("ok", true));

        verify(users).selectByIdForUpdate(userId);
        org.mockito.ArgumentCaptor<NotificationEventEntity> captured =
                org.mockito.ArgumentCaptor.forClass(NotificationEventEntity.class);
        verify(notifications).insert(captured.capture());
        assertEquals(5L, captured.getValue().getSequenceNo());
    }

    @Test
    void teamSequenceIsAllocatedWhileTeamRowIsLocked() {
        TeamEventMapper teams = mock(TeamEventMapper.class);
        qg.qgent.mapper.TeamMapper teamRows = mock(qg.qgent.mapper.TeamMapper.class);
        UUID teamId = UUID.randomUUID();
        when(teams.maxSequence(teamId)).thenReturn(7L);
        EventService service = fullService(mock(EventMapper.class), mock(NotificationEventMapper.class), teams,
                mock(UserMapper.class), teamRows);

        service.publishTeamEvent(teamId, "activity.created", "resource-1", Map.of("ok", true));

        verify(teamRows).selectByIdForUpdate(teamId);
        org.mockito.ArgumentCaptor<TeamEventEntity> captured =
                org.mockito.ArgumentCaptor.forClass(TeamEventEntity.class);
        verify(teams).insert(captured.capture());
        assertEquals(8L, captured.getValue().getSequenceNo());
    }

    @Test
    void mrFirstPreflightAndCqApprovalPublishTypedDomainEvents() {
        EventMapper events = mock(EventMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID dryRunId = UUID.randomUUID();
        when(events.nextSequence(projectId)).thenReturn(1L, 2L);
        EventService service = service(events, projects, publisher);

        service.publish(projectId, null, "mr-first.preflight.requested", taskId.toString(),
                Map.of("taskId", taskId));
        service.publish(projectId, null, "preflight.updated", dryRunId.toString(),
                Map.of("taskId", taskId, "decision", "APPROVED"));

        org.mockito.ArgumentCaptor<Object> captured = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(captured.capture());
        assertEquals(taskId, ((MrFirstPreflightRequestedDomainEvent) captured.getAllValues().get(0)).taskId());
        PreflightCqApprovedDomainEvent approval = (PreflightCqApprovedDomainEvent) captured.getAllValues().get(1);
        assertEquals(projectId, approval.projectId());
        assertEquals(dryRunId, approval.dryRunId());
    }

    private EventService service(EventMapper events, ProjectMapper projects) {
        return service(events, projects, mock(ApplicationEventPublisher.class));
    }

    private EventService service(EventMapper events, ProjectMapper projects, ApplicationEventPublisher publisher) {
        return new EventService(events, mock(ProjectAccessService.class), mock(NotificationEventMapper.class),
                mock(TeamEventMapper.class), publisher, mock(RealtimeHub.class),
                mock(ProjectMemberMapper.class), mock(TeamMemberMapper.class),
                mock(RequirementGroupMapper.class), mock(GroupMemberMapper.class), projects);
    }

    private EventService fullService(EventMapper events, NotificationEventMapper notifications,
                                     TeamEventMapper teams, UserMapper users, TeamMapper teamRows) {
        return new EventService(events, mock(ProjectAccessService.class), notifications, teams,
                mock(ApplicationEventPublisher.class), mock(RealtimeHub.class), mock(ProjectMemberMapper.class),
                mock(TeamMemberMapper.class), mock(RequirementGroupMapper.class), mock(GroupMemberMapper.class),
                mock(ProjectMapper.class), users, teamRows);
    }
}
