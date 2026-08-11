package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.AttachmentCreateRequest;
import qg.qgent.service.AttachmentService;

import java.util.UUID;

/**
 * 附件直传凭证接口（契约 §7 创建对象存储直传凭证）。
 * <p>
 * 写操作（POST）的 Idempotency-Key 由 {@code IdempotencyFilter} 统一强制与回放。
 */
@RestController
@RequestMapping("/api/v1")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * 创建附件直传凭证（需 Idempotency-Key）。
     */
    @PostMapping("/projects/{projectId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> createCredential(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @Valid @RequestBody AttachmentCreateRequest body, HttpServletRequest request) {
        return ok(attachmentService.createCredential(userId, projectId, body), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
