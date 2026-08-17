package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.entity.EventEntity;
import qg.qgent.mapper.EventMapper;
import qg.qgent.mapper.NotificationEventMapper;
import qg.qgent.mapper.TeamEventMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 事件发布与过期清理的事务边界测试。 */
class EventServiceTest {

    @Test
    void publishOnlyPersistsEventWithoutRunningCleanupSql() {
        EventMapper events = mock(EventMapper.class);
        UUID projectId = UUID.randomUUID();
        when(events.maxSequence(projectId)).thenReturn(8L);
        EventService service = service(events);

        service.publish(projectId, null, "task-run.updated", "run-1", Map.of("status", "RUNNING"));

        verify(events).maxSequence(projectId);
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
        EventService service = service(events);

        service.purgeExpired();

        verify(events).deleteExpired(any());
        verifyNoMoreInteractions(events);
    }

    private EventService service(EventMapper events) {
        return new EventService(events, mock(ProjectAccessService.class), mock(NotificationEventMapper.class),
                mock(TeamEventMapper.class));
    }
}
