package qg.qgent.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.AttachmentCreateRequest;
import qg.qgent.service.AttachmentService;
import qg.qgent.service.IdempotencyService;

import java.util.UUID;

/**
 * 附件直传凭证接口（契约 §7 创建对象存储直传凭证）。
 */
@RestController
@RequestMapping("/api/v1")
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public AttachmentController(AttachmentService attachmentService, IdempotencyService idempotency,
            ObjectMapper mapper) {
        this.attachmentService = attachmentService;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    /**
     * 创建附件直传凭证（写操作，需 Idempotency-Key）。
     */
    @PostMapping("/projects/{projectId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> createCredential(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AttachmentCreateRequest body, HttpServletRequest request) {
        JsonNode result = idempotency.run("POST:/api/v1/projects/{projectId}/attachments", idempotencyKey, userId,
                body, HttpStatus.CREATED.value(),
                () -> mapper.valueToTree(ok(attachmentService.createCredential(userId, projectId, body), request)));
        return fromJson(result);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    private ApiResponse<?> fromJson(JsonNode node) {
        try {
            return mapper.treeToValue(node, ApiResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应解析失败", e);
        }
    }
}
