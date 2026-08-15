package qg.qgent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.AttachmentCreateRequest;
import qg.qgent.dto.AttachmentDownloadUrlResponse;
import qg.qgent.dto.AttachmentStatusResponse;
import qg.qgent.dto.AttachmentUploadCredentialResponse;
import qg.qgent.dto.UploadCredential;
import qg.qgent.entity.AttachmentEntity;
import qg.qgent.mapper.AttachmentMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 附件直传凭证与生命周期业务（契约 §7）。
 * <p>
 * 流程：先落 PENDING 附件记录并签发对象存储直传凭证 → 客户端把文件直接上传到对象存储 →
 * 客户端确认上传（服务端校验对象存在后置 READY）→ 按需签发临时下载地址。
 * 文件字节始终不经过后端；上传/下载均为对象存储预签名 URL。
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

    private AttachmentEntity requireAttachment(UUID projectId, UUID attachmentId) {
        AttachmentEntity attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null || !projectId.equals(attachment.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在或无权访问");
        }
        return attachment;
    }
}
