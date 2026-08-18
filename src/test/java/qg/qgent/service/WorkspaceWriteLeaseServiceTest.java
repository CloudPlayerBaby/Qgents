package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.mapper.WorkspaceMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 持久化 Workspace 写租约的 CAS 语义测试，不启动数据库。 */
class WorkspaceWriteLeaseServiceTest {

    @Test
    void acquireReturnsOpaqueTokenOnlyAfterDatabaseCasWins() {
        WorkspaceMapper mapper = mock(WorkspaceMapper.class);
        UUID projectId = UUID.randomUUID(), workspaceId = UUID.randomUUID(), taskId = UUID.randomUUID();
        when(mapper.claimWriteLease(eq(projectId), eq(workspaceId), eq(taskId), any())).thenReturn(1);

        WorkspaceWriteLeaseService service = new WorkspaceWriteLeaseService(mapper);
        WorkspaceWriteLease lease = service.acquire(projectId, workspaceId, taskId);

        assertEquals(projectId, lease.getProjectId());
        assertEquals(workspaceId, lease.getWorkspaceId());
        assertEquals(taskId, lease.getTaskId());
        assertNotNull(lease.token());
    }

    @Test
    void acquireRejectsOtherActiveTaskWithoutOverwritingIt() {
        WorkspaceMapper mapper = mock(WorkspaceMapper.class);
        when(mapper.claimWriteLease(any(), any(), any(), any())).thenReturn(0);

        ApiException error = assertThrows(ApiException.class, () -> new WorkspaceWriteLeaseService(mapper)
                .acquire(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("WORKSPACE_WRITE_LEASE_HELD", error.code());
    }

    @Test
    void renewalOrReleaseCanOnlyUseTheOwningToken() {
        WorkspaceMapper mapper = mock(WorkspaceMapper.class);
        UUID projectId = UUID.randomUUID(), workspaceId = UUID.randomUUID(), taskId = UUID.randomUUID();
        WorkspaceWriteLease lease = new WorkspaceWriteLease(projectId, workspaceId, taskId, "lease-token");
        when(mapper.renewWriteLease(projectId, workspaceId, taskId, "lease-token")).thenReturn(0);
        WorkspaceWriteLeaseService service = new WorkspaceWriteLeaseService(mapper);

        ApiException error = assertThrows(ApiException.class, () -> service.renew(lease));
        assertEquals("WORKSPACE_WRITE_LEASE_LOST", error.code());

        service.release(lease);
        verify(mapper).releaseWriteLease(projectId, workspaceId, taskId, "lease-token");
    }
}
