package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.api.ApiException;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
    void publishDefersBusinessTransactionEventsUntilAfterCommitAndPreservesOrder() {
        EventMapper events = mock(EventMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        UUID projectId = UUID.randomUUID();
        when(events.nextSequence(projectId)).thenReturn(1L, 2L);
        EventService service = service(events, projects);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.publish(projectId, null, "task.updated", "task-1", Map.of("status", "RUNNING"));
            service.publish(projectId, null, "task-step.updated", "step-1", Map.of("status", "RUNNING"));

            verify(events, never()).insert(any(EventEntity.class));
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.forEach(TransactionSynchronization::afterCommit);

            org.mockito.ArgumentCaptor<EventEntity> captured = org.mockito.ArgumentCaptor.forClass(EventEntity.class);
            verify(events, org.mockito.Mockito.times(2)).insert(captured.capture());
            assertEquals(List.of("task.updated", "task-step.updated"),
                    captured.getAllValues().stream().map(EventEntity::getEventType).toList());
            synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
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
    void deadlockIsRetriedInRequiresNewTransactionsAndDoesNotReachCaller() {
        EventMapper events = mock(EventMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        UUID projectId = UUID.randomUUID();
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        doNothing().when(transactions).commit(any(TransactionStatus.class));
        doThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .doThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .doNothing().when(events).insert(any(EventEntity.class));
        when(events.nextSequence(projectId)).thenReturn(1L);
        EventService service = productionService(events, projects, transactions);

        service.publish(projectId, null, "task-run.updated", "run-1", Map.of("status", "FAILED"));

        verify(transactions, org.mockito.Mockito.times(3)).getTransaction(any(TransactionDefinition.class));
        verify(events, org.mockito.Mockito.times(3)).insert(any(EventEntity.class));
        verify(projects, org.mockito.Mockito.times(3)).selectByIdForUpdate(projectId);
    }

    private EventService service(EventMapper events, ProjectMapper projects) {
        return new EventService(events, mock(ProjectAccessService.class), mock(NotificationEventMapper.class),
                mock(TeamEventMapper.class), mock(RealtimeHub.class),
                mock(ProjectMemberMapper.class), mock(TeamMemberMapper.class),
                mock(RequirementGroupMapper.class), mock(GroupMemberMapper.class), projects);
    }

    private EventService productionService(EventMapper events, ProjectMapper projects,
                                           PlatformTransactionManager transactions) {
        return new EventService(events, mock(ProjectAccessService.class), mock(NotificationEventMapper.class),
                mock(TeamEventMapper.class), mock(RealtimeHub.class),
                mock(ProjectMemberMapper.class), mock(TeamMemberMapper.class), mock(RequirementGroupMapper.class),
                mock(GroupMemberMapper.class), projects, null, null, transactions, null);
    }

    private EventService fullService(EventMapper events, NotificationEventMapper notifications,
                                     TeamEventMapper teams, UserMapper users, TeamMapper teamRows) {
        return new EventService(events, mock(ProjectAccessService.class), notifications, teams,
                mock(RealtimeHub.class), mock(ProjectMemberMapper.class),
                mock(TeamMemberMapper.class), mock(RequirementGroupMapper.class), mock(GroupMemberMapper.class),
                mock(ProjectMapper.class), users, teamRows);
    }

    @Test
    void sseConnectionLimitRejectsExcessStreams() throws Exception {
        EventMapper events = mock(EventMapper.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        UUID projectId = UUID.randomUUID(), userId = UUID.randomUUID();
        EventService service = new EventService(events, access, mock(NotificationEventMapper.class),
                mock(TeamEventMapper.class), mock(RealtimeHub.class),
                mock(ProjectMemberMapper.class), mock(TeamMemberMapper.class),
                mock(RequirementGroupMapper.class), mock(GroupMemberMapper.class), mock(ProjectMapper.class),
                mock(UserMapper.class), mock(TeamMapper.class), null, null);

        // 把活跃连接计数压到上限，验证新连接被拒绝（429），不会无限堆积泵线程。
        java.lang.reflect.Field active = EventService.class.getDeclaredField("activeSseConnections");
        active.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger counter =
                (java.util.concurrent.atomic.AtomicInteger) active.get(service);
        java.lang.reflect.Field max = EventService.class.getDeclaredField("MAX_SSE_CONNECTIONS");
        max.setAccessible(true);
        counter.set(max.getInt(null));

        when(events.maxSequence(projectId)).thenReturn(1L);
        ApiException error = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.stream(projectId, userId, null));
        assertEquals("SSE_CONNECTION_LIMIT_EXCEEDED", error.code());
        // 拒绝的连接不占用计数
        assertEquals(max.getInt(null), counter.get());
    }

    @Test
    void sseStreamAcquiresConnectionSlot() throws Exception {
        EventMapper events = mock(EventMapper.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        UUID projectId = UUID.randomUUID(), userId = UUID.randomUUID();
        EventService service = new EventService(events, access, mock(NotificationEventMapper.class),
                mock(TeamEventMapper.class), mock(RealtimeHub.class),
                mock(ProjectMemberMapper.class), mock(TeamMemberMapper.class),
                mock(RequirementGroupMapper.class), mock(GroupMemberMapper.class), mock(ProjectMapper.class),
                mock(UserMapper.class), mock(TeamMapper.class), null, null);
        when(events.maxSequence(projectId)).thenReturn(1L);

        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                service.stream(projectId, userId, null);
        assertNotNull(emitter);

        java.lang.reflect.Field active = EventService.class.getDeclaredField("activeSseConnections");
        active.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger counter =
                (java.util.concurrent.atomic.AtomicInteger) active.get(service);
        assertEquals(1, counter.get());
        // 连接槽位随 emitter 关闭由容器回调释放（onCompletion/onError/onTimeout）；
        // 单元测试不依赖容器线程，这里只验证成功建立时计数占用正确。
    }
}
