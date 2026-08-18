package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.config.PushProperties;
import qg.qgent.dto.PushDeviceRegistrationRequest;
import qg.qgent.dto.PushDeviceResponse;
import qg.qgent.entity.NotificationEntity;
import qg.qgent.entity.PushDeliveryEntity;
import qg.qgent.entity.PushDeviceEntity;
import qg.qgent.mapper.NotificationMapper;
import qg.qgent.mapper.PushDeliveryMapper;
import qg.qgent.mapper.PushDeviceMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 移动端推送注册与 Outbox 调度服务。
 * 设备 Token 只在内存中短暂出现，数据库保存摘要和 AES-GCM 密文；投递成功前不会把通知标记为已送达。
 */
@Service
public class PushNotificationService {
    private final PushDeviceMapper deviceMapper;
    private final PushDeliveryMapper deliveryMapper;
    private final NotificationMapper notificationMapper;
    private final PushTokenCipher tokenCipher;
    private final PushGateway gateway;
    private final PushProperties properties;

    public PushNotificationService(PushDeviceMapper deviceMapper, PushDeliveryMapper deliveryMapper,
                                   NotificationMapper notificationMapper, PushTokenCipher tokenCipher,
                                   PushGateway gateway, PushProperties properties) {
        this.deviceMapper = deviceMapper;
        this.deliveryMapper = deliveryMapper;
        this.notificationMapper = notificationMapper;
        this.tokenCipher = tokenCipher;
        this.gateway = gateway;
        this.properties = properties;
    }

    /** 注册或刷新一个安装实例；相同用户与 installationId 重试只更新同一行。 */
    @Transactional
    public PushDeviceResponse register(UUID userId, PushDeviceRegistrationRequest request) {
        if (!properties.deliveryConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PUSH_NOT_CONFIGURED", "离线推送服务尚未配置");
        }
        String installationId = request.getInstallationId().trim();
        String token = request.getToken().trim();
        PushDeviceEntity existing = deviceMapper.selectInstallation(userId, installationId);
        String hash = tokenCipher.hash(token);
        if (existing == null) {
            PushDeviceEntity entity = new PushDeviceEntity();
            entity.setId(UuidV7.next());
            entity.setUserId(userId);
            entity.setInstallationId(installationId);
            entity.setPlatform(request.getPlatform());
            entity.setProvider("FCM");
            entity.setTokenHash(hash);
            entity.setTokenCiphertext(tokenCipher.encrypt(token));
            entity.setActive(true);
            try {
                deviceMapper.insert(entity);
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "PUSH_DEVICE_CONFLICT", "设备推送注册冲突，请重试");
            }
            return view(entity);
        }
        existing.setPlatform(request.getPlatform());
        existing.setTokenHash(hash);
        existing.setTokenCiphertext(tokenCipher.encrypt(token));
        existing.setActive(true);
        deviceMapper.updateById(existing);
        return view(existing);
    }

    /** 注销安装实例；重复注销不报错。 */
    @Transactional
    public void unregister(UUID userId, String installationId) {
        PushDeviceEntity existing = deviceMapper.selectInstallation(userId, installationId);
        if (existing != null && Boolean.TRUE.equals(existing.getActive())) {
            existing.setActive(false);
            deviceMapper.updateById(existing);
        }
    }

    /** 通知落库后创建每台活动设备一条幂等 Outbox 记录。 */
    @Transactional
    public void enqueue(NotificationEntity notification) {
        if (notification == null || notification.getRecipientUserId() == null) return;
        for (PushDeviceEntity device : deviceMapper.selectActiveByUser(notification.getRecipientUserId())) {
            PushDeliveryEntity delivery = new PushDeliveryEntity();
            delivery.setId(UuidV7.next());
            delivery.setNotificationId(notification.getId());
            delivery.setDeviceId(device.getId());
            delivery.setStatus("PENDING");
            delivery.setAttemptCount(0);
            delivery.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC));
            try {
                deliveryMapper.insert(delivery);
            } catch (DuplicateKeyException ignored) {
                // 同一通知/设备的重试只保留一条投递事实。
            }
        }
    }

    /** 定时认领并投递 Outbox；多实例通过条件更新保证同一条记录只有一个发送者。 */
    @Scheduled(fixedDelayString = "${qgents.push.poll-interval-ms:5000}")
    public void dispatchDue() {
        if (!properties.deliveryConfigured()) return;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        deliveryMapper.recoverStale(now.minusMinutes(5), now);
        int maxAttempts = Math.max(1, Math.min(properties.getMaxAttempts(), 20));
        List<PushDeliveryEntity> due = deliveryMapper.selectDue(now, maxAttempts, 50);
        for (PushDeliveryEntity candidate : due) {
            if (deliveryMapper.claim(candidate.getId(), now) == 0) continue;
            PushDeliveryEntity delivery = deliveryMapper.selectById(candidate.getId());
            PushDeviceEntity device = delivery == null ? null : deviceMapper.selectById(delivery.getDeviceId());
            NotificationEntity notification = delivery == null ? null : notificationMapper.selectById(delivery.getNotificationId());
            if (delivery == null || device == null || notification == null || !Boolean.TRUE.equals(device.getActive())) {
                deliveryMapper.markFailed(candidate.getId(), "PUSH_TARGET_NOT_FOUND", now.plusDays(3650), now);
                continue;
            }
            try {
                PushSendResult result = gateway.send(tokenCipher.decrypt(device.getTokenCiphertext()), notification);
                deliveryMapper.markSent(delivery.getId(), result.getProviderMessageId(), now);
            } catch (PushGatewayException e) {
                if (e.isInvalidToken()) {
                    device.setActive(false);
                    deviceMapper.updateById(device);
                }
                int attempt = Math.max(1, delivery.getAttemptCount() == null ? 1 : delivery.getAttemptCount());
                long backoffSeconds = Math.min(300, 1L << Math.min(attempt, 8));
                deliveryMapper.markFailed(delivery.getId(), e.getCode(), now.plusSeconds(backoffSeconds), now);
            } catch (RuntimeException e) {
                deliveryMapper.markFailed(delivery.getId(), "PUSH_DELIVERY_FAILED", now.plusMinutes(5), now);
            }
        }
    }

    private PushDeviceResponse view(PushDeviceEntity entity) {
        return new PushDeviceResponse(entity.getId().toString(), entity.getInstallationId(), entity.getPlatform(),
                Boolean.TRUE.equals(entity.getActive()));
    }
}
