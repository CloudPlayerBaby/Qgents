package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.service.event.DeliveryStartedDomainEvent;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MrFirstDeliveryServiceTest {
    @Test
    void committedDomainEventStartsTheClaimedSystemBatch() {
        Fixture fixture = new Fixture();
        fixture.batch.setDeliveryClaimToken("claim-1");
        when(fixture.tasks.selectById(fixture.task.getId())).thenReturn(fixture.task);
        when(fixture.batches.selectById(fixture.batch.getId())).thenReturn(fixture.batch);

        fixture.service.onDeliveryStarted(new DeliveryStartedDomainEvent(fixture.projectId, fixture.task.getId(),
                fixture.batch.getId(), fixture.batch.getDeliveryOperationId()));

        verify(fixture.delivery).deliverSystemAcceptedBatch(fixture.projectId, fixture.task.getId(),
                fixture.batch.getId(), "claim-1");
    }

    @Test
    void mismatchedOperationDoesNotStartDelivery() {
        Fixture fixture = new Fixture();
        when(fixture.tasks.selectById(fixture.task.getId())).thenReturn(fixture.task);
        when(fixture.batches.selectById(fixture.batch.getId())).thenReturn(fixture.batch);

        fixture.service.onDeliveryStarted(new DeliveryStartedDomainEvent(fixture.projectId, fixture.task.getId(),
                fixture.batch.getId(), "other-operation"));

        verify(fixture.delivery, never()).deliverSystemAcceptedBatch(any(), any(), any(), any());
    }

    @Test
    void expiredSystemLeaseIsReclaimedAndDelivered() {
        Fixture fixture = new Fixture();
        fixture.batch.setDeliveryLeaseExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        when(fixture.batches.selectList(any())).thenReturn(List.of(fixture.batch));
        when(fixture.batches.selectByIdForUpdate(fixture.batch.getId())).thenReturn(fixture.batch);
        when(fixture.tasks.selectById(fixture.task.getId())).thenReturn(fixture.task);
        when(fixture.batches.selectById(fixture.batch.getId())).thenReturn(fixture.batch);

        fixture.service.recoverStuckDeliveries();

        verify(fixture.batches).updateById(fixture.batch);
        verify(fixture.delivery).deliverSystemAcceptedBatch(fixture.projectId, fixture.task.getId(),
                fixture.batch.getId(), fixture.batch.getDeliveryClaimToken());
    }

    @Test
    void transientDeliveryFailureRelinquishesCurrentClaimForPromptRecovery() {
        Fixture fixture = new Fixture();
        fixture.batch.setDeliveryClaimToken("claim-1");
        when(fixture.tasks.selectById(fixture.task.getId())).thenReturn(fixture.task);
        when(fixture.batches.selectById(fixture.batch.getId())).thenReturn(fixture.batch);
        doThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "SANDBOX_WORKER_UNAVAILABLE", "worker unavailable"))
                .when(fixture.delivery).deliverSystemAcceptedBatch(fixture.projectId, fixture.task.getId(),
                        fixture.batch.getId(), "claim-1");

        fixture.service.onDeliveryStarted(new DeliveryStartedDomainEvent(fixture.projectId, fixture.task.getId(),
                fixture.batch.getId(), fixture.batch.getDeliveryOperationId()));

        verify(fixture.delivery).relinquishSystemDeliveryClaim(fixture.projectId, fixture.task.getId(),
                fixture.batch.getId(), "claim-1");
    }

    private static final class Fixture {
        private final UUID projectId = UUID.randomUUID();
        private final TaskMapper tasks = mock(TaskMapper.class);
        private final DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        private final DiffReviewBatchService delivery = mock(DiffReviewBatchService.class);
        private final TaskEntity task = new TaskEntity();
        private final DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        private final MrFirstDeliveryService service;

        private Fixture() {
            task.setId(UUID.randomUUID());
            task.setProjectId(projectId);
            task.setStatus("DELIVERING");
            task.setDeliveryMode("MR_FIRST");
            batch.setId(UUID.randomUUID());
            batch.setProjectId(projectId);
            batch.setTaskId(task.getId());
            batch.setConfirmationSource("SYSTEM");
            batch.setDeliveryStatus("DELIVERING");
            batch.setDeliveryOperationId("operation-1");
            service = new MrFirstDeliveryService(batches, tasks, delivery, immediateTransactions());
        }
    }

    @SuppressWarnings("unchecked")
    private static TransactionTemplate immediateTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return transactions;
    }
}
