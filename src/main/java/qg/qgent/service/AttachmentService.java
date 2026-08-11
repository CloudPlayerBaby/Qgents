package qg.qgent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.AttachmentCreateRequest;
import qg.qgent.dto.AttachmentUploadCredentialResponse;
import qg.qgent.entity.AttachmentEntity;
import qg.qgent.mapper.AttachmentMapper;

import java.util.UUID;

/**
 * 附件直传凭证业务：先落 PENDING 附件记录，再签发对象存储直传凭证（契约 §7）。
 */
@Service
public class AttachmentService {
    private final AttachmentMapper attachmentMapper;
    private final AttachmentStorageStrategy storage;
    private final ProjectAccessService access;

    public AttachmentService(AttachmentMapper attachmentMapper, AttachmentStorageStrategy storage,
            ProjectAccessService access) {
        this.attachmentMapper = attachmentMapper;
        this.storage = storage;
        this.access = access;
    }

    /**
     * 创建附件直传凭证。
     * <p>
     * 仅在项目成员可访问的范围内创建 PENDING 附件记录并签发短时有效凭证；
     * 上传完成前不绑定消息，凭证不含任何凭据明文。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param body      创建请求
     * @return 直传凭证
     */
    @Transactional
    public AttachmentUploadCredentialResponse createCredential(UUID actor, UUID projectId,
            AttachmentCreateRequest body) {
        access.requireMember(projectId, actor);
        AttachmentEntity attachment = new AttachmentEntity();
        attachment.setId(UuidV7.next());
        attachment.setProjectId(projectId);
        attachment.setUploadedBy(actor);
        attachment.setObjectKey("projects/" + projectId + "/attachments/" + attachment.getId());
        attachment.setFileName(body.getFileName().trim());
        attachment.setMediaType(body.getContentType());
        attachment.setSizeBytes(body.getSizeBytes());
        attachment.setStatus("PENDING");
        attachmentMapper.insert(attachment);

        AttachmentStorageStrategy.UploadCredential credential = storage.createCredential(attachment.getId(),
                attachment.getFileName(), attachment.getMediaType(), attachment.getSizeBytes());
        return new AttachmentUploadCredentialResponse(attachment.getId().toString(), credential.uploadUrl(),
                credential.method(), credential.expiresAt(), credential.headers());
    }
}
