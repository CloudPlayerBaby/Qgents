package qg.qgent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import qg.qgent.api.ApiException;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.AttachmentPreviewUrlResponse;
import qg.qgent.service.AttachmentService;
import qg.qgent.service.AttachmentService.AttachmentPreviewContent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 附件内联预览控制器单测：安全链建立认证主体后的 Range 206/416、raw 语义、preview-url 包装。
 * <p>
 * 直接调用控制器方法（与项目既有控制器测试一致），不接受 query token。
 */
class AttachmentControllerTest {

    private final AttachmentService service = mock(AttachmentService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AttachmentController controller = new AttachmentController(service, objectMapper);
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(RequestIdFilter.ATTRIBUTE)).thenReturn("req-1");
    }

    @Test
    void previewServesRangeForAuthenticatedPrincipal() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        byte[] data = "hello qwen".getBytes(StandardCharsets.UTF_8);
        when(service.previewContent(actor, projectId, attachmentId)).thenReturn(
                new AttachmentPreviewContent(data, "TEXT", "notes.txt", "text/plain", (long) data.length));

        ResponseEntity<byte[]> resp = controller.preview(projectId, attachmentId, null, "bytes=0-4", actor, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 0-4/" + data.length);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(resp.getBody()).isEqualTo(Arrays.copyOfRange(data, 0, 5));
    }

    @Test
    void previewSupportsSuffixRange() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        when(service.previewContent(actor, projectId, attachmentId)).thenReturn(
                new AttachmentPreviewContent(data, "TEXT", "notes.txt", "text/plain", (long) data.length));

        ResponseEntity<byte[]> resp = controller.preview(projectId, attachmentId, null, "bytes=-3", actor, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-4/" + data.length);
        assertThat(resp.getBody()).isEqualTo("llo".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void previewReturns416ForUnsatisfiableRange() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        when(service.previewContent(actor, projectId, attachmentId)).thenReturn(
                new AttachmentPreviewContent(data, "TEXT", "notes.txt", "text/plain", (long) data.length));

        ResponseEntity<byte[]> resp = controller.preview(projectId, attachmentId, null, "bytes=99-100", actor, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */" + data.length);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void previewRejectsMissingAuth() {
        assertThatThrownBy(() -> controller.preview(UUID.randomUUID(), UUID.randomUUID(), null, null, null, request))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(401);
                    assertThat(((ApiException) e).code()).isEqualTo("UNAUTHORIZED");
                });
    }

    @Test
    void previewServesFullContentForAuthenticatedPrincipal() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        when(service.previewContent(actor, projectId, attachmentId)).thenReturn(
                new AttachmentPreviewContent(data, "CODE", "Main.java", "application/octet-stream", (long) data.length));

        ResponseEntity<byte[]> resp = controller.preview(projectId, attachmentId, null, null, actor, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(resp.getBody()).isEqualTo(data);
    }

    @Test
    void previewRawReturnsOriginalMediaType() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        byte[] data = "def x = 1".getBytes(StandardCharsets.UTF_8);
        when(service.previewContent(actor, projectId, attachmentId)).thenReturn(
                new AttachmentPreviewContent(data, "CODE", "Main.java", "application/octet-stream", (long) data.length));

        ResponseEntity<byte[]> resp = controller.preview(projectId, attachmentId, 1, null, actor, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("application/octet-stream");
        assertThat(resp.getBody()).isEqualTo(data);
    }

    @Test
    void previewUrlWrapsServiceResponse() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        AttachmentPreviewUrlResponse dto = new AttachmentPreviewUrlResponse();
        dto.setAttachmentId(attachmentId.toString());
        dto.setPreviewable(true);
        dto.setPreviewType("IMAGE");
        when(service.previewUrl(actor, projectId, attachmentId)).thenReturn(dto);

        ApiResponse<?> resp = controller.previewUrl(actor, projectId, attachmentId, request);

        assertThat(resp.data()).isSameAs(dto);
    }

    @Test
    void uploadDelegatesRawBytesToService() throws IOException {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        MockHttpServletRequest req = new MockHttpServletRequest("PUT",
                "/api/v1/projects/" + projectId + "/attachments/" + attachmentId);
        req.setContent("file-bytes".getBytes(StandardCharsets.UTF_8));

        controller.upload(actor, projectId, attachmentId, "application/octet-stream", req);

        verify(service).uploadBytes(eq(actor), eq(projectId), eq(attachmentId), any(InputStream.class),
                eq("application/octet-stream"));
    }
}
