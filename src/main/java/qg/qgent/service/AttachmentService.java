package qg.qgent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.*;
import qg.qgent.entity.AttachmentEntity;
import qg.qgent.mapper.AttachmentMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 附件直传凭证与生命周期业务（契约 §7）。
 * <p>
 * 流程：先落 PENDING 附件记录并签发代理上传凭证 → 客户端把文件字节 PUT 到代理端点
 * （{@link #uploadBytes} 写入当前存储策略）→ 客户端确认上传（服务端校验对象存在后置 READY）→
 * 按需签发临时下载地址。上传字节经后端代理写入存储（OSS / 本地磁盘），下载为预签名 URL 或流式回源。
 */
@Service
public class AttachmentService {
    private final AttachmentMapper attachmentMapper;
    private final AttachmentStorageStrategy storage;
    private final ProjectAccessService access;
    private final long maxSizeBytes;
    private final long downloadExpirySeconds;

    public AttachmentService(AttachmentMapper attachmentMapper, AttachmentStorageStrategy storage,
                             ProjectAccessService access,
                             @Value("${app.attachment-max-size-bytes:52428800}") long maxSizeBytes,
                             @Value("${app.attachment-download-expiry-seconds:900}") long downloadExpirySeconds) {
        this.attachmentMapper = attachmentMapper;
        this.storage = storage;
        this.access = access;
        this.maxSizeBytes = maxSizeBytes;
        this.downloadExpirySeconds = downloadExpirySeconds;
    }

    /**
     * 创建附件直传凭证。
     * <p>
     * 仅在项目成员可访问的范围内创建 PENDING 附件记录并签发短时有效凭证；校验文件大小在上限内。
     * 凭证不含任何凭据明文。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param body      创建请求
     * @return 直传凭证
     */
    @Transactional
    public AttachmentUploadCredentialResponse createCredential(UUID actor, UUID projectId,
                                                               AttachmentCreateRequest body) {
        access.requireProjectMember(projectId, actor);
        long sizeBytes = body.getSizeBytes();
        if (sizeBytes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_SIZE_REQUIRED", "文件大小必须大于 0");
        }
        if (sizeBytes > maxSizeBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE", "文件大小超过上限 " + maxSizeBytes + " 字节");
        }
        AttachmentEntity attachment = new AttachmentEntity();
        attachment.setId(UuidV7.next());
        attachment.setProjectId(projectId);
        attachment.setUploadedBy(actor);
        attachment.setObjectKey("projects/" + projectId + "/attachments/" + attachment.getId());
        attachment.setFileName(body.getFileName().trim());
        attachment.setMediaType(body.getContentType());
        attachment.setSizeBytes(sizeBytes);
        attachment.setStatus("PENDING");
        attachmentMapper.insert(attachment);

        UploadCredential credential = storage.createCredential(attachment.getId(), attachment.getObjectKey(),
                attachment.getFileName(), attachment.getMediaType(), attachment.getSizeBytes());
        return new AttachmentUploadCredentialResponse(attachment.getId().toString(), credential.getUploadUrl(),
                credential.getMethod(), credential.getExpiresAt(), credential.getHeaders());
    }

    /**
     * 为已上传附件签发临时下载（预签名 GET）地址。
     * <p>
     * 校验项目成员资格与附件归属；不支持下载的策略（本地开发回退）抛 501。
     *
     * @param actor        当前用户 ID
     * @param projectId    项目 ID
     * @param attachmentId 附件 ID
     * @return 预签名下载地址
     */
    public AttachmentDownloadUrlResponse downloadUrl(UUID actor, UUID projectId, UUID attachmentId) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(downloadExpirySeconds);
        String url;
        try {
            url = storage.createDownloadUrl(attachment.getObjectKey(), downloadExpirySeconds);
        } catch (UnsupportedOperationException e) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "ATTACHMENT_DOWNLOAD_UNSUPPORTED",
                    "当前存储策略暂不支持下载（请启用阿里云 OSS 或接入对象存储）");
        }
        return new AttachmentDownloadUrlResponse(attachment.getId().toString(), url, expiresAt);
    }

    /**
     * 客户端上传完成后确认附件：校验对象已真实存在于存储，并置 READY。
     * <p>
     * 仅 PENDING 可确认；已确认/失败/删除的附件返回 409。无法校验存在的策略按存在处理。
     *
     * @param actor        当前用户 ID
     * @param projectId    项目 ID
     * @param attachmentId 附件 ID
     * @return 附件状态
     */
    @Transactional
    public AttachmentStatusResponse confirmUpload(UUID actor, UUID projectId, UUID attachmentId) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        if (!"PENDING".equals(attachment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_PENDING", "附件已确认或不可确认");
        }
        if (!storage.objectExists(attachment.getObjectKey())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_UPLOADED", "附件尚未上传到对象存储，请先上传再确认");
        }
        attachment.setStatus("READY");
        attachmentMapper.updateById(attachment);
        return new AttachmentStatusResponse(attachment.getId().toString(), attachment.getStatus());
    }

    /**
     * 服务端代理接收附件字节并写入存储（契约 §7 代理上传）。
     * <p>
     * 校验项目成员资格、附件归属与 PENDING 状态后，读取请求体字节（上限 {@code maxSizeBytes}）写入
     * 当前存储策略；不改状态，仍由 confirm 在 objectExists 校验通过后置 READY。
     *
     * @param actor        当前用户 ID
     * @param projectId    项目 ID
     * @param attachmentId 附件 ID
     * @param bytes        请求体原始字节流
     * @param contentType  请求 Content-Type，可空
     */
    public void uploadBytes(UUID actor, UUID projectId, UUID attachmentId, InputStream bytes, String contentType) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        if (!"PENDING".equals(attachment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_PENDING", "附件不可上传（状态非待上传）");
        }
        byte[] data = readBounded(bytes);
        if (data.length > maxSizeBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE",
                    "文件大小超过上限 " + maxSizeBytes + " 字节");
        }
        storage.storeBytes(attachment.getObjectKey(), new ByteArrayInputStream(data), contentType);
    }

    /**
     * 读取请求体字节，边读边按 maxSizeBytes 上限拦截超限上传，避免把超大请求整体读入内存。
     */
    private byte[] readBounded(InputStream in) {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = stream.read(buf)) != -1) {
                total += n;
                if (total > maxSizeBytes) {
                    throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE",
                            "文件大小超过上限 " + maxSizeBytes + " 字节");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_READ_FAILED", "读取附件内容失败");
        }
    }

    /**
     * 鉴权后流式读取附件内容（内容下载代理，供 content.url 稳定展示）。
     * <p>
     * 校验项目成员资格、附件归属与 READY 状态后，从对象存储读取内容流；
     * 不支持的存储策略（本地开发回退）返回 501。调用方负责关闭流。
     *
     * @param actor        当前用户 ID
     * @param projectId    项目 ID
     * @param attachmentId 附件 ID
     * @return 附件内容流与元数据
     */
    public AttachmentContent downloadContent(UUID actor, UUID projectId, UUID attachmentId) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        if (!"READY".equals(attachment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_READY", "附件尚未上传完成");
        }
        try {
            return storage.loadContent(attachment.getObjectKey(), attachment.getFileName(), attachment.getMediaType());
        } catch (UnsupportedOperationException e) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "ATTACHMENT_CONTENT_UNSUPPORTED",
                    "当前存储策略暂不支持内容下载（请启用阿里云 OSS）");
        }
    }

    /**
     * 为已上传附件签发内联预览元数据与短期签名预览地址（契约 §4）。
     * <p>
     * 校验项目成员资格、附件归属与 READY 后，返回不含凭证的预览地址；浏览器通过同站 HttpOnly Cookie
     * 建立认证。下载地址不可用（本地存储回退）时 downloadUrl 为 null，前端可用 previewUrl 完成下载。
     *
     * @param actor        当前用户 ID
     * @param projectId    项目 ID
     * @param attachmentId 附件 ID
     * @return 预览元数据与签名预览地址
     */
    public AttachmentPreviewUrlResponse previewUrl(UUID actor, UUID projectId, UUID attachmentId) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        if (!"READY".equals(attachment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_READY", "附件尚未上传完成");
        }
        String previewType = classifyPreviewType(attachment);
        String downloadUrl = null;
        try {
            downloadUrl = storage.createDownloadUrl(attachment.getObjectKey(), downloadExpirySeconds);
        } catch (UnsupportedOperationException e) {
            // 本地存储回退：不签发下载地址；previewUrl 仍可用（preview 对不支持的内容读取返回 501）
        }
        String previewUrl = "/api/v1/projects/" + projectId + "/attachments/" + attachmentId + "/preview";
        return new AttachmentPreviewUrlResponse(attachment.getId().toString(), attachment.getFileName(),
                attachment.getMediaType(), attachment.getSizeBytes(),
                !"UNSUPPORTED".equals(previewType), previewType, previewUrl, downloadUrl, null);
    }

    /**
     * 读取附件的预览元数据（类型 + 声明大小），不读取字节、不签发 token。
     * <p>
     * 供多模态输入链路在读取附件字节前先按 previewType 与大小预算裁剪：IMAGE 按字节预算转媒体、
     * TEXT/CODE 按预算内联文本，避免把超大附件整体读入内存再丢弃。读取失败或越权由调用方按
     * 预算跳过，不影响主流程。
     *
     * @param actor        当前用户 ID
     * @param projectId    项目 ID
     * @param attachmentId 附件 ID
     * @return 预览类型与声明大小
     */
    public AttachmentPreviewMetadata previewMetadata(UUID actor, UUID projectId, UUID attachmentId) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        if (!"READY".equals(attachment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_READY", "附件尚未上传完成");
        }
        return new AttachmentPreviewMetadata(classifyPreviewType(attachment), attachment.getSizeBytes());
    }

    /**
     * 供消息列表回显时回填 IMAGE/FILE 消息的预览字段（增量契约 §7 增强：previewable / previewType）。
     * <p>
     * 调用方已校验群成员可见性（群属于本项目），此处只校验附件归属与 READY；不签发带 token 的
     * previewUrl —— 消息内容里的 URL 无法随 token 过期续期，前端拿到过期地址后没有回退，会再次
     * 出现「无法预览」。previewable/previewType 无时效性，前端据此提示「点击预览」并走 preview-url
     * 接口（带 5 分钟 Query 缓存）重新签发。
     *
     * @param projectId    附件所属项目 ID
     * @param attachmentId 附件 ID（字符串形式）
     * @return 预览字段；附件不存在、不属于本项目或未 READY 时返回 null（调用方保持原样回显）
     */
    public MessageAttachmentPreview messagePreview(UUID projectId, String attachmentId) {
        UUID id;
        try {
            id = UUID.fromString(attachmentId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
        AttachmentEntity attachment = attachmentMapper.selectById(id);
        if (attachment == null || !projectId.equals(attachment.getProjectId())) {
            return null;
        }
        if (!"READY".equals(attachment.getStatus())) {
            return null;
        }
        String previewType = classifyPreviewType(attachment);
        return new MessageAttachmentPreview(!"UNSUPPORTED".equals(previewType), previewType);
    }

    /**
     * 读取附件全部字节用于内联预览（契约 §5）。
     * <p>
     * 校验项目成员资格、附件归属与 READY 后从对象存储读取全部字节；本地存储回退返回 501。
     * 控制器负责按 previewType 与 Range 组织响应头。字节整体读入内存，受附件大小上限约束，
     * 适合图片/PDF/文本预览场景。
     *
     * @param actor        当前用户 ID
     * @param projectId    项目 ID
     * @param attachmentId 附件 ID
     * @return 附件字节与预览类型元数据
     */
    public AttachmentPreviewContent previewContent(UUID actor, UUID projectId, UUID attachmentId) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        if (!"READY".equals(attachment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_READY", "附件尚未上传完成");
        }
        AttachmentContent loaded;
        try {
            loaded = storage.loadContent(attachment.getObjectKey(), attachment.getFileName(), attachment.getMediaType());
        } catch (UnsupportedOperationException e) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "ATTACHMENT_CONTENT_UNSUPPORTED",
                    "当前存储策略暂不支持内容读取（请启用阿里云 OSS）");
        }
        return new AttachmentPreviewContent(readAll(loaded.getStream()), classifyPreviewType(attachment),
                attachment.getFileName(), attachment.getMediaType(), attachment.getSizeBytes());
    }

    private byte[] readAll(InputStream in) {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_READ_FAILED", "读取附件内容失败");
        }
    }

    /** CODE 判定用 MIME：结构化文本/代码文件。 */
    private static final Set<String> CODE_MEDIA_TYPES = Set.of(
            "application/json", "application/xml", "application/javascript", "application/x-javascript",
            "application/x-yaml", "application/x-sh", "application/x-python");

    /** CODE 判定用文件扩展名（契约 §2.1）。 */
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "ts", "tsx", "js", "jsx", "py", "go", "rs", "c", "cpp", "cc", "h", "hpp",
            "sql", "sh", "bash", "zsh", "json", "yaml", "yml", "xml", "md", "markdown", "properties",
            "css", "scss", "html", "htm", "vue", "gradle", "toml", "ini", "cfg", "conf");

    /** TEXT 判定用扩展名（mediaType 未知时的兜底）。 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "log");

    /**
     * 按 mediaType 与文件名扩展名判定预览类型（契约 §2.1）。
     * <p>
     * 以 mediaType 为主、扩展名兜底；IMAGE/PDF/TEXT/CODE 可直接内联预览，其余为
     * UNSUPPORTED（前端回退为下载按钮）。判定结果随 preview-url 返回给前端。
     */
    private String classifyPreviewType(AttachmentEntity attachment) {
        String mediaType = attachment.getMediaType();
        if (mediaType != null) {
            String lower = mediaType.toLowerCase(Locale.ROOT).trim();
            if (lower.startsWith("image/")) {
                return "IMAGE";
            }
            if ("application/pdf".equals(lower)) {
                return "PDF";
            }
            if (lower.startsWith("text/")) {
                return "TEXT";
            }
            if (CODE_MEDIA_TYPES.contains(lower)) {
                return "CODE";
            }
        }
        String fileName = attachment.getFileName();
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT).trim();
                if (CODE_EXTENSIONS.contains(ext)) {
                    return "CODE";
                }
                if (TEXT_EXTENSIONS.contains(ext)) {
                    return "TEXT";
                }
            }
        }
        return "UNSUPPORTED";
    }

    /**
     * 预览元数据载体：附件预览类型与声明大小，不含字节（非接口 DTO，仅服务内部传递）。
     */
    public static class AttachmentPreviewMetadata {
        private final String previewType;
        private final Long sizeBytes;

        public AttachmentPreviewMetadata(String previewType, Long sizeBytes) {
            this.previewType = previewType;
            this.sizeBytes = sizeBytes;
        }

        public String previewType() {
            return previewType;
        }

        public Long sizeBytes() {
            return sizeBytes;
        }
    }

    /**
     * 消息回显预览字段载体（增量契约 §7：previewable / previewType，无时效性；非接口 DTO）。
     */
    public static class MessageAttachmentPreview {
        private final boolean previewable;
        private final String previewType;

        public MessageAttachmentPreview(boolean previewable, String previewType) {
            this.previewable = previewable;
            this.previewType = previewType;
        }

        public boolean isPreviewable() {
            return previewable;
        }

        public String getPreviewType() {
            return previewType;
        }
    }

    /**
     * 预览内容载体：附件全部字节与预览元数据（非接口 DTO，仅服务内部传递）。
     */
    public static class AttachmentPreviewContent {
        private final byte[] bytes;
        private final String previewType;
        private final String fileName;
        private final String mediaType;
        private final Long sizeBytes;

        public AttachmentPreviewContent(byte[] bytes, String previewType, String fileName, String mediaType, Long sizeBytes) {
            this.bytes = bytes;
            this.previewType = previewType;
            this.fileName = fileName;
            this.mediaType = mediaType;
            this.sizeBytes = sizeBytes;
        }

        public byte[] bytes() {
            return bytes;
        }

        public String previewType() {
            return previewType;
        }

        public String fileName() {
            return fileName;
        }

        public String mediaType() {
            return mediaType;
        }

        public Long sizeBytes() {
            return sizeBytes;
        }
    }

    private AttachmentEntity requireAttachment(UUID projectId, UUID attachmentId) {
        AttachmentEntity attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null || !projectId.equals(attachment.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在或无权访问");
        }
        return attachment;
    }
}
