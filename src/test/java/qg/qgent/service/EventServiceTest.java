package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import qg.qgent.entity.EventEntity;
import qg.qgent.mapper.EventMapper;
import qg.qgent.mapper.GroupMemberMapper;
import qg.qgent.mapper.NotificationEventMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.TeamEventMapper;
import qg.qgent.mapper.TeamMemberMapper;
import qg.qgent.service.event.DeliveryStartedDomainEvent;

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

    private EventService service(EventMapper events, ProjectMapper projects) {
        return service(events, projects, mock(ApplicationEventPublisher.class));
    }

    private EventService service(EventMapper events, ProjectMapper projects, ApplicationEventPublisher publisher) {
        return new EventService(events, mock(ProjectAccessService.class), mock(NotificationEventMapper.class),
                mock(TeamEventMapper.class), publisher, mock(RealtimeHub.class),
                mock(ProjectMemberMapper.class), mock(TeamMemberMapper.class),
                mock(RequirementGroupMapper.class), mock(GroupMemberMapper.class), projects);
    }
}
