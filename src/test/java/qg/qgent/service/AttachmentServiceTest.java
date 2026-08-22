package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.dto.AttachmentPreviewUrlResponse;
import qg.qgent.entity.AttachmentEntity;
import qg.qgent.mapper.AttachmentMapper;
import qg.qgent.service.AttachmentService.AttachmentPreviewContent;
import qg.qgent.service.AttachmentService.AttachmentPreviewMetadata;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 附件内联预览服务单测：previewUrl / previewContent / previewType 分类 / 鉴权与状态拒绝。
 * <p>
 * 使用 Mockito 隔离 Mapper/存储/项目访问；预览地址不得包含认证凭证。
 */
class AttachmentServiceTest {

    private final AttachmentMapper mapper = mock(AttachmentMapper.class);
    private final AttachmentStorageStrategy storage = mock(AttachmentStorageStrategy.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final AttachmentService service = new AttachmentService(mapper, storage, access, 52428800, 900);

    @Test
    void previewUrlReturnsCanonicalCookieProtectedUrlAndMetadata() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "photo.png", "image/png", "READY"));
        when(storage.createDownloadUrl(eq("projects/" + projectId + "/attachments/" + attachmentId), eq(900L)))
                .thenReturn("https://oss.example/photo.png");

        AttachmentPreviewUrlResponse r = service.previewUrl(actor, projectId, attachmentId);

        assertThat(r.getAttachmentId()).isEqualTo(attachmentId.toString());
        assertThat(r.getFileName()).isEqualTo("photo.png");
        assertThat(r.getMediaType()).isEqualTo("image/png");
        assertThat(r.getPreviewType()).isEqualTo("IMAGE");
        assertThat(r.isPreviewable()).isTrue();
        assertThat(r.getDownloadUrl()).isEqualTo("https://oss.example/photo.png");
        assertThat(r.getPreviewUrl()).isEqualTo("/api/v1/projects/" + projectId + "/attachments/" + attachmentId
                + "/preview");
        assertThat(r.getExpiresAt()).isNull();
    }

    @Test
    void previewUrlKeepsPreviewUrlWhenDownloadUnsupported() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "doc.pdf", "application/pdf", "READY"));
        doThrow(new UnsupportedOperationException()).when(storage).createDownloadUrl(any(), anyLong());

        AttachmentPreviewUrlResponse r = service.previewUrl(actor, projectId, attachmentId);

        assertThat(r.getDownloadUrl()).isNull();
        assertThat(r.getPreviewUrl()).doesNotContain("?");
    }

    @Test
    void previewUrlRejectsNotReady() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "x.txt", "text/plain", "PENDING"));

        assertThatThrownBy(() -> service.previewUrl(UUID.randomUUID(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_READY");
                    assertThat(((ApiException) e).status().value()).isEqualTo(409);
                });
    }

    @Test
    void previewUrlRejectsAttachmentNotInProject() {
        UUID projectId = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, otherProject, "x.txt", "text/plain", "READY"));

        assertThatThrownBy(() -> service.previewUrl(UUID.randomUUID(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void previewUrlRejectsMissingAttachment() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(null);

        assertThatThrownBy(() -> service.previewUrl(UUID.randomUUID(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void previewMetadataReturnsTypeAndDeclaredSizeWithoutReadingBytes() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        AttachmentEntity entity = attachment(attachmentId, projectId, "photo.png", "image/png", "READY");
        entity.setSizeBytes(102400L);
        when(mapper.selectById(attachmentId)).thenReturn(entity);

        AttachmentPreviewMetadata meta = service.previewMetadata(actor, projectId, attachmentId);

        assertThat(meta.previewType()).isEqualTo("IMAGE");
        assertThat(meta.sizeBytes()).isEqualTo(102400L);
    }

    @Test
    void previewMetadataRejectsNotReady() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "x.txt", "text/plain", "PENDING"));

        assertThatThrownBy(() -> service.previewMetadata(UUID.randomUUID(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_READY"));
    }

    @Test
    void previewContentReadsBytesAndClassifiesType() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        byte[] payload = "hello qwen".getBytes(StandardCharsets.UTF_8);
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "doc.pdf", "application/pdf", "READY"));
        when(storage.loadContent(any(), any(), any())).thenReturn(
                new AttachmentContent(new ByteArrayInputStream(payload), "application/pdf", "doc.pdf", (long) payload.length));

        AttachmentPreviewContent pc = service.previewContent(actor, projectId, attachmentId);

        assertThat(pc.previewType()).isEqualTo("PDF");
        assertThat(pc.bytes()).isEqualTo(payload);
        assertThat(pc.mediaType()).isEqualTo("application/pdf");
        assertThat(pc.fileName()).isEqualTo("doc.pdf");
    }

    @Test
    void previewContentRejectsNotReady() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "x.txt", "text/plain", "FAILED"));

        assertThatThrownBy(() -> service.previewContent(UUID.randomUUID(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_READY");
                    assertThat(((ApiException) e).status().value()).isEqualTo(409);
                });
    }

    @Test
    void previewContentReturns501WhenStorageUnsupported() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "x.txt", "text/plain", "READY"));
        when(storage.loadContent(any(), any(), any())).thenThrow(new UnsupportedOperationException());

        assertThatThrownBy(() -> service.previewContent(UUID.randomUUID(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_CONTENT_UNSUPPORTED");
                    assertThat(((ApiException) e).status().value()).isEqualTo(501);
                });
    }

    @Test
    void uploadBytesStoresPendingAttachmentBytes() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        byte[] payload = "hello qwen".getBytes(StandardCharsets.UTF_8);
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "doc.pdf", "application/pdf", "PENDING"));

        service.uploadBytes(actor, projectId, attachmentId, new ByteArrayInputStream(payload), "application/pdf");

        verify(storage).storeBytes(eq("projects/" + projectId + "/attachments/" + attachmentId),
                any(InputStream.class), eq("application/pdf"));
    }

    @Test
    void uploadBytesRejectsNotPending() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "x.txt", "text/plain", "READY"));

        assertThatThrownBy(() -> service.uploadBytes(UUID.randomUUID(), projectId, attachmentId,
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_PENDING");
                    assertThat(((ApiException) e).status().value()).isEqualTo(409);
                });
    }

    @Test
    void uploadBytesRejectsMissingAttachment() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(null);

        assertThatThrownBy(() -> service.uploadBytes(UUID.randomUUID(), projectId, attachmentId,
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void uploadBytesRejectsExceedingMaxSize() {
        AttachmentService small = new AttachmentService(mapper, storage, access, 10, 900);
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        byte[] oversized = "more-than-ten-bytes".getBytes(StandardCharsets.UTF_8);
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "big.txt", "text/plain", "PENDING"));

        assertThatThrownBy(() -> small.uploadBytes(UUID.randomUUID(), projectId, attachmentId,
                new ByteArrayInputStream(oversized), "text/plain"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_TOO_LARGE");
                    assertThat(((ApiException) e).status().value()).isEqualTo(413);
                });
    }

    @Test
    void uploadBytesRejectsNonMember() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问"))
                .when(access).requireProjectMember(projectId, actor);

        assertThatThrownBy(() -> service.uploadBytes(actor, projectId, attachmentId,
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void classifiesPreviewTypesByMediaTypeAndExtension() {
        assertPreviewType("photo.png", "image/png", "IMAGE");
        assertPreviewType("doc.pdf", "application/pdf", "PDF");
        assertPreviewType("notes.txt", "text/plain", "TEXT");
        assertPreviewType("config.json", "application/json", "CODE");
        assertPreviewType("Main.java", null, "CODE");
        assertPreviewType("script.sh", "application/x-sh", "CODE");
        assertPreviewType("readme.md", "text/markdown", "TEXT");
        assertPreviewType("data.bin", "application/octet-stream", "UNSUPPORTED");
    }

    private void assertPreviewType(String fileName, String mediaType, String expected) {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, fileName, mediaType, "READY"));
        doThrow(new UnsupportedOperationException()).when(storage).createDownloadUrl(any(), anyLong());

        AttachmentPreviewUrlResponse r = service.previewUrl(UUID.randomUUID(), projectId, attachmentId);

        assertThat(r.getPreviewType()).as("%s (%s) 应为 %s", fileName, mediaType, expected).isEqualTo(expected);
    }

    private AttachmentEntity attachment(UUID id, UUID projectId, String fileName, String mediaType, String status) {
        AttachmentEntity value = new AttachmentEntity();
        value.setId(id);
        value.setProjectId(projectId);
        value.setObjectKey("projects/" + projectId + "/attachments/" + id);
        value.setFileName(fileName);
        value.setMediaType(mediaType);
        value.setStatus(status);
        return value;
    }
}
