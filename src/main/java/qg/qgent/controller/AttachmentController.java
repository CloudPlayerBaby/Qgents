package qg.qgent.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiException;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.auth.TokenService;
import qg.qgent.dto.AttachmentCreateRequest;
import qg.qgent.dto.AttachmentDownloadUrlResponse;
import qg.qgent.dto.AttachmentStatusResponse;
import qg.qgent.service.AttachmentContent;
import qg.qgent.service.AttachmentService;
import qg.qgent.service.AttachmentService.AttachmentPreviewContent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    public AttachmentController(AttachmentService attachmentService, TokenService tokenService,
                                ObjectMapper objectMapper) {
        this.attachmentService = attachmentService;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
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
     * 契约 §7：服务端代理接收上传——客户端把文件字节 PUT 到后端，由服务端写入当前存储策略（OSS / 本地磁盘）。
     * <p>
     * 鉴权（JWT）与幂等（Idempotency-Key，/api/v1/projects/** 写接口强制）由安全链承担；
     * 请求体为原始文件字节，Content-Type 可选。响应 204，随后客户端照常调用 confirm 置 READY。
     */
    @Operation(summary = "代理上传附件字节", description = "客户端把文件字节 PUT 到后端，服务端写入当前存储策略（OSS/本地磁盘）；响应 204，随后调用 confirm 置 READY。")
    @PutMapping("/projects/{projectId}/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upload(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                       @PathVariable UUID attachmentId,
                       @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                       HttpServletRequest request) throws IOException {
        attachmentService.uploadBytes(userId, projectId, attachmentId, request.getInputStream(), contentType);
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

    /**
     * 契约 §4：获取附件内联预览元数据与短期签名预览地址。
     * <p>
     * 返回的 previewUrl 为带短期 access token 的相对路径，浏览器/系统查看器直接使用，无需任何请求头；
     * token 只进 URL，不得写入日志、异常或 SSE 事件。downloadUrl 在本地存储回退时为 null，
     * 此时前端可用 previewUrl 完成下载。
     */
    @Operation(summary = "获取附件预览元数据与签名预览地址", description = "返回预览类型与短期签名预览地址（带 token），前端可直接交给 img/iframe/系统查看器打开。")
    @GetMapping("/projects/{projectId}/attachments/{attachmentId}/preview-url")
    public ApiResponse<?> previewUrl(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                     @PathVariable UUID attachmentId, HttpServletRequest request) {
        return ok(attachmentService.previewUrl(userId, projectId, attachmentId), request);
    }

    /**
     * 契约 §5：附件内联内容代理（inline + Range + 双通道鉴权）。
     * <p>
     * 同时支持 {@code Authorization: Bearer} 头与 {@code ?token=} 查询参数，二选一即可；浏览器
     * &lt;img&gt;/新标签页/iframe 用 query token，App/原生下载器用 Authorization 头。无任一有效凭证
     * 返回 401；token 有效但非项目成员由服务层返回 403。文本/代码默认返回 UTF-8 纯文本，
     * {@code ?raw=1} 时原样返回原始 mediaType 字节。支持标准 HTTP Range 分段（PDF 分页、大文件流式）。
     */
    @Operation(summary = "获取附件内联预览内容（双通道鉴权 + Range）", description = "内联返回附件字节，浏览器/系统查看器直接展示；支持 Authorization 头或 ?token= 双通道鉴权与 HTTP Range 分段。")
    @GetMapping("/projects/{projectId}/attachments/{attachmentId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable UUID projectId, @PathVariable UUID attachmentId,
            @RequestParam(name = "token", required = false) String token,
            @RequestParam(name = "raw", required = false) Integer raw,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @AuthenticationPrincipal UUID userId,
            HttpServletRequest request) {
        UUID actor = userId;
        if (actor == null && token != null && !token.isBlank()) {
            actor = tokenService.verifyAccess(token);
        }
        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "需要登录");
        }
        AttachmentPreviewContent pc = attachmentService.previewContent(actor, projectId, attachmentId);
        byte[] data = pc.bytes();
        String contentType = resolvePreviewContentType(pc.previewType(), pc.mediaType(), raw);
        ContentDisposition cd = ContentDisposition.builder("inline")
                .filename(pc.fileName() == null || pc.fileName().isBlank() ? "preview" : pc.fileName(),
                        StandardCharsets.UTF_8)
                .build();
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            long[] range = parseRange(rangeHeader, data.length);
            if (range == null) {
                return rangeNotSatisfiable(data.length, request);
            }
            int start = (int) range[0];
            int end = (int) range[1];
            byte[] slice = Arrays.copyOfRange(data, start, end + 1);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + data.length)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(slice.length)
                    .body(slice);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(data.length)
                .body(data);
    }

    /**
     * 按预览类型解析响应 Content-Type：文本/代码默认 UTF-8 纯文本；raw=1 或非文本类型返回原 mediaType。
     */
    private String resolvePreviewContentType(String previewType, String mediaType, Integer raw) {
        if ("TEXT".equals(previewType) || "CODE".equals(previewType)) {
            if (raw != null && raw == 1 && mediaType != null && !mediaType.isBlank()) {
                return mediaType;
            }
            return "text/plain; charset=utf-8";
        }
        return mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
    }

    /**
     * 解析标准 Range 头（仅支持单段 bytes=start-end / start- / -suffix）。
     *
     * @return {start, end}；头部缺失或非法（越界/格式错误/多段）返回 null，由调用方按 416 处理。
     */
    private long[] parseRange(String header, int total) {
        if (!header.startsWith("bytes=")) {
            return null;
        }
        String spec = header.substring("bytes=".length()).trim();
        if (spec.contains(",")) {
            return null; // 多段 Range 暂不支持
        }
        String[] parts = spec.split("-", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            long start;
            long end;
            String startPart = parts[0].trim();
            String endPart = parts[1].trim();
            if (startPart.isEmpty()) {
                // 后缀式 bytes=-N：返回最后 N 字节
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0 || total <= 0) {
                    return null;
                }
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(startPart);
                end = endPart.isEmpty() ? total - 1 : Long.parseLong(endPart);
                if (start < 0 || start >= total || end < start) {
                    return null;
                }
                end = Math.min(end, total - 1);
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 构建 416 Range Not Satisfiable 响应：携带 {@code Content-Range: bytes *&#47;<size>} 与统一错误体。
     */
    private ResponseEntity<byte[]> rangeNotSatisfiable(long total, HttpServletRequest request) {
        Map<String, Object> body = Map.of(
                "error", Map.of("code", "ATTACHMENT_RANGE_INVALID",
                        "message", "Range 越界或格式非法", "details", List.of()),
                "requestId", String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化错误响应失败", e);
        }
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    /**
     * 鉴权后流式返回附件内容（内容下载代理，稳定展示地址）。
     * <p>
     * 每次请求都做项目成员与附件归属校验，图片走 inline 渲染，其余走 attachment 下载；
     * content.url 可直接填本路径（项目内稳定、长期有效）。
     */
    @Operation(summary = "获取附件内容（鉴权下载代理）", description = "鉴权后流式返回附件内容，图片 inline 展示、文件可下载；作为 content.url 的稳定地址。")
    @GetMapping("/projects/{projectId}/attachments/{attachmentId}/content")
    public ResponseEntity<InputStreamResource> content(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID projectId, @PathVariable UUID attachmentId) {
        AttachmentContent content = attachmentService.downloadContent(userId, projectId, attachmentId);
        String disposition = content.getContentType() != null && content.getContentType().startsWith("image/")
                ? "inline"
                : "attachment";
        ContentDisposition cd = ContentDisposition.builder(disposition)
                .filename(content.getFileName() == null || content.getFileName().isBlank() ? "attachment"
                        : content.getFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.getContentType()))
                .contentLength(content.getSizeBytes() == null ? -1 : content.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(new InputStreamResource(content.getStream()));
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
