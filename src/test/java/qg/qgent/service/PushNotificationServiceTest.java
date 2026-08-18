package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import qg.qgent.config.PushProperties;
import qg.qgent.entity.NotificationEntity;
import qg.qgent.entity.PushDeviceEntity;
import qg.qgent.entity.PushDeliveryEntity;
import qg.qgent.mapper.NotificationMapper;
import qg.qgent.mapper.PushDeliveryMapper;
import qg.qgent.mapper.PushDeviceMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 推送 Outbox 的通知/设备唯一键是重复通知重试的最终防线。 */
class PushNotificationServiceTest {

    @Test
    void duplicateOutboxInsertIsTreatedAsIdempotentRetry() {
        PushDeviceMapper devices = mock(PushDeviceMapper.class);
        PushDeliveryMapper deliveries = mock(PushDeliveryMapper.class);
        NotificationEntity notification = notification();
        PushDeviceEntity device = new PushDeviceEntity();
        device.setId(UUID.randomUUID());
        when(devices.selectActiveByUser(notification.getRecipientUserId())).thenReturn(List.of(device));
        when(deliveries.insert(any(PushDeliveryEntity.class))).thenThrow(new DuplicateKeyException("duplicate"));
        PushNotificationService service = service(devices, deliveries, mock(PushGateway.class));

        service.enqueue(notification);

        verify(deliveries).insert(any(PushDeliveryEntity.class));
    }

    @Test
    void disabledProviderDoesNotClaimOutboxRows() {
        PushDeliveryMapper deliveries = mock(PushDeliveryMapper.class);
        PushProperties properties = new PushProperties();
        PushNotificationService service = new PushNotificationService(mock(PushDeviceMapper.class), deliveries,
                mock(NotificationMapper.class), mock(PushTokenCipher.class), mock(PushGateway.class), properties);

        service.dispatchDue();

        verify(deliveries, never()).selectDue(any(), anyInt(), anyInt());
    }

    private PushNotificationService service(PushDeviceMapper devices, PushDeliveryMapper deliveries,
                                            PushGateway gateway) {
        return new PushNotificationService(devices, deliveries, mock(NotificationMapper.class),
                mock(PushTokenCipher.class), gateway, new PushProperties());
    }

    private NotificationEntity notification() {
        NotificationEntity notification = new NotificationEntity();
        notification.setId(UUID.randomUUID());
        notification.setRecipientUserId(UUID.randomUUID());
        return notification;
    }
}
