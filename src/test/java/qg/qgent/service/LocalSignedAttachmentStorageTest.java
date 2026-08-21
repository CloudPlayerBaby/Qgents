package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.dto.UploadCredential;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地磁盘附件存储单测：凭证代理路径、字节落盘/回读/存在性、对象键路径逃逸防护。
 */
class LocalSignedAttachmentStorageTest {

    @TempDir
    Path root;

    private LocalSignedAttachmentStorage storage() {
        return new LocalSignedAttachmentStorage(root.toString(), 900);
    }

    @Test
    void credentialPointsToProxyUploadPath() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        String objectKey = "projects/" + projectId + "/attachments/" + attachmentId;

        UploadCredential credential = storage().createCredential(attachmentId, objectKey, "a.png", "image/png", 10L);

        assertThat(credential.getUploadUrl()).isEqualTo("/api/v1/" + objectKey);
        assertThat(credential.getMethod()).isEqualTo("PUT");
        assertThat(credential.getHeaders()).containsEntry("Content-Type", "image/png");
        assertThat(credential.getExpiresAt()).isNotNull();
    }

    @Test
    void storesAndLoadsBytesAndChecksExistence() throws Exception {
        LocalSignedAttachmentStorage storage = storage();
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        String objectKey = "projects/" + projectId + "/attachments/" + attachmentId;
        byte[] payload = "hello qwen".getBytes(StandardCharsets.UTF_8);

        assertThat(storage.objectExists(objectKey)).isFalse();

        storage.storeBytes(objectKey, new ByteArrayInputStream(payload), "text/plain");

        assertThat(storage.objectExists(objectKey)).isTrue();
        AttachmentContent content = storage.loadContent(objectKey, "notes.txt", "text/plain");
        assertThat(content.getFileName()).isEqualTo("notes.txt");
        assertThat(content.getContentType()).isEqualTo("text/plain");
        assertThat(content.getStream().readAllBytes()).isEqualTo(payload);
    }

    @Test
    void rejectsPathTraversalObjectKey() {
        LocalSignedAttachmentStorage storage = storage();
        assertThatThrownBy(() -> storage.storeBytes("../evil.txt", new ByteArrayInputStream(new byte[0]), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.loadContent("../../etc/passwd", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
