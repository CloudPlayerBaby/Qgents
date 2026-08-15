package qg.qgent.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.AttachmentCreateRequest;
import qg.qgent.dto.AttachmentDownloadUrlResponse;
import qg.qgent.dto.AttachmentStatusResponse;
import qg.qgent.service.AttachmentService;

import java.util.UUID;

/**
 * 附件直传凭证与生命周期接口
 * <p>
 * 流程：签发直传凭证 → 客户端直接上传到对象存储 → 确认上传置 READY → 按需签发临时下载地址。
 * 所有接口不返回对象存储长期密钥。
 */
@RestController
@RequestMapping("/api/v1")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * 契约 §7：创建对象存储直传凭证。
     */
    @Operation(summary = "创建附件直传凭证", description = "返回当前项目附件的短期直传地址；前端将文件直接上传后再确认附件。")
    @PostMapping("/projects/{projectId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> createCredential(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                           @Valid @RequestBody AttachmentCreateRequest body, HttpServletRequest request) {
        return ok(attachmentService.createCredential(userId, projectId, body), request);
    }

    /**
     * 契约 §7：客户端上传到对象存储后确认附件：服务端校验对象存在并置 READY。
     */
    @Operation(summary = "确认附件上传完成", description = "客户端将文件上传到对象存储后调用，服务端校验对象存在并置 READY。")
    @PostMapping("/projects/{projectId}/attachments/{attachmentId}/confirm")
    public ApiResponse<?> confirm(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                  @PathVariable UUID attachmentId, HttpServletRequest request) {
        AttachmentStatusResponse data = attachmentService.confirmUpload(userId, projectId, attachmentId);
        return ok(data, request);
    }

    /**
     * 契约 §7：为已上传附件签发临时下载（预签名 GET）地址。
     */
    @Operation(summary = "获取附件临时下载地址", description = "返回短期有效的预签名 GET 地址，客户端据此下载/预览文件；过期需重新签发。")
    @GetMapping("/projects/{projectId}/attachments/{attachmentId}/download-url")
    public ApiResponse<?> downloadUrl(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                      @PathVariable UUID attachmentId, HttpServletRequest request) {
        AttachmentDownloadUrlResponse data = attachmentService.downloadUrl(userId, projectId, attachmentId);
        return ok(data, request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
