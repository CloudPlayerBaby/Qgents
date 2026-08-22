package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import qg.qgent.entity.NotificationEntity;
import qg.qgent.mapper.NotificationMapper;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    private final NotificationMapper mapper = mock(NotificationMapper.class);
    private final EventService events = mock(EventService.class);

    @Test
    void repeatedMrStatusDoesNotCreateAnotherNotification() {
        UUID recipient = UUID.randomUUID();
        String mrId = UUID.randomUUID().toString();
        doThrow(new DuplicateKeyException("duplicate MR status"))
                .when(mapper).insert(any(NotificationEntity.class));
        NotificationService service = new NotificationService(mapper, events);

        service.notifyMrStatus(recipient, UUID.randomUUID(), null, "MR 状态更新：1", "OPEN", mrId, "OPEN");

        verify(mapper).insert(any(NotificationEntity.class));
        verifyNoInteractions(events);
    }

    @Test
    void changedMrStatusCreatesNotification() {
        UUID recipient = UUID.randomUUID();
        String mrId = UUID.randomUUID().toString();
        NotificationService service = new NotificationService(mapper, events);

        service.notifyMrStatus(recipient, UUID.randomUUID(), null, "MR 状态更新：1", "MERGED", mrId, "MERGED");

        verify(mapper).insert(any(NotificationEntity.class));
        verify(events).publishNotification(eq(recipient), any(), eq("notification.created"), any());
        ArgumentCaptor<NotificationEntity> notification = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(mapper).insert(notification.capture());
        assertEquals(mrId + ":MERGED", notification.getValue().getDedupeKey());
    }
}
