package qg.qgent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.stereotype.Component;
import qg.qgent.config.PushProperties;
import qg.qgent.entity.NotificationEntity;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 使用 FCM HTTP v1 调用受控配置的推送服务；响应只提取消息 ID 或稳定错误码。 */
@Component
public class FcmPushGateway implements PushGateway {
    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private final PushProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private volatile GoogleCredentials credentials;

    public FcmPushGateway(PushProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public PushSendResult send(String deviceToken, NotificationEntity notification) {
        if (!properties.deliveryConfigured()) {
            throw new PushGatewayException("PUSH_PROVIDER_NOT_CONFIGURED", false);
        }
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("token", deviceToken);
            message.put("notification", Map.of("title", notification.getTitle(),
                    "body", notification.getDescription() == null ? "" : notification.getDescription()));
            Map<String, String> data = new LinkedHashMap<>();
            data.put("notificationId", notification.getId().toString());
            data.put("kind", notification.getKind());
            put(data, "projectId", notification.getProjectId());
            put(data, "groupId", notification.getRequirementGroupId());
            if (notification.getResourceId() != null) data.put("resourceId", notification.getResourceId());
            message.put("data", data);
            // Android 熄屏时应由系统以高优先级唤醒；iOS 是否展示由 App 的 APNs 配置决定。
            message.put("android", Map.of("priority", "high"));
            message.put("apns", Map.of("headers", Map.of("apns-priority", "10"),
                    "payload", Map.of("aps", Map.of("content-available", 1))));
            Map<String, Object> body = Map.of("message", message);

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw providerError(response.statusCode(), response.body());
            }
            JsonNode root = mapper.readTree(response.body());
            String messageId = root.path("name").asText(null);
            if (messageId == null || messageId.isBlank()) {
                throw new PushGatewayException("PUSH_PROVIDER_RESPONSE_INVALID", false);
            }
            return new PushSendResult(messageId);
        } catch (PushGatewayException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PushGatewayException("PUSH_PROVIDER_INTERRUPTED", false);
        } catch (Exception e) {
            throw new PushGatewayException("PUSH_PROVIDER_UNAVAILABLE", false);
        }
    }

    private String endpoint() {
        return properties.getFcmEndpoint().replaceAll("/+$", "") + "/"
                + properties.getFcmProjectId() + "/messages:send";
    }

    private String accessToken() throws Exception {
        GoogleCredentials current = credentials;
        if (current == null) {
            synchronized (this) {
                current = credentials;
                if (current == null) {
                    byte[] encoded = Base64.getDecoder().decode(properties.getFcmServiceAccountJsonBase64());
                    current = GoogleCredentials.fromStream(new ByteArrayInputStream(encoded))
                            .createScoped(List.of(FCM_SCOPE));
                    credentials = current;
                }
            }
        }
        current.refreshIfExpired();
        if (current.getAccessToken() == null || current.getAccessToken().getTokenValue() == null) {
            throw new PushGatewayException("PUSH_PROVIDER_AUTH_FAILED", false);
        }
        return current.getAccessToken().getTokenValue();
    }

    private PushGatewayException providerError(int status, String rawBody) {
        try {
            String providerStatus = mapper.readTree(rawBody).path("error").path("status").asText("");
            boolean invalidToken = "UNREGISTERED".equals(providerStatus)
                    || "INVALID_ARGUMENT".equals(providerStatus);
            if (invalidToken) return new PushGatewayException("PUSH_TOKEN_INVALID", true);
            if (status == 401 || status == 403) return new PushGatewayException("PUSH_PROVIDER_AUTH_FAILED", false);
        } catch (Exception ignored) {
            // Provider body 不可信且不进入日志；无法解析时只返回稳定错误码。
        }
        return new PushGatewayException("PUSH_PROVIDER_HTTP_" + status, false);
    }

    private void put(Map<String, String> target, String key, Object value) {
        if (value != null) target.put(key, value.toString());
    }
}
