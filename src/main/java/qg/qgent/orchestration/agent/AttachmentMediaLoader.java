package qg.qgent.orchestration.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import qg.qgent.api.ApiException;
import qg.qgent.dto.ContextMessage;
import qg.qgent.service.AttachmentService;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 从群聊消息中按项目成员身份读取附件并转换为多模态输入：
 * <ul>
 *   <li>IMAGE 附件转 base64 data URI {@link Media}，供多模态模型视觉理解；</li>
 *   <li>FILE 附件中的 TEXT/CODE 内联为文本；</li>
 *   <li>读取失败、越权、越预算或类型不支持的附件降级为文本引用，不影响主流程。</li>
 * </ul>
 * 供 Plan / Coding 等 Agent 复用，避免各自复制一份图片/文件读取逻辑。
 */
@Slf4j
@Component
public class AttachmentMediaLoader {

    /**
     * 图片媒体字节预算：base64 后约 5.2MB，防止超大截图/长图撑爆多模态输入与 token 成本。
     * 超预算的图片降级为文本引用。
     */
    public static final long IMAGE_BYTES_CAP = 3_900_000L;

    /**
     * 文本/代码附件内联文本预算：超过 64KB 不读入 prompt，仅保留文件引用，避免稀释上下文。
     */
    public static final long FILE_TEXT_BYTES_CAP = 64 * 1024L;

    private final AttachmentService attachmentService;

    public AttachmentMediaLoader(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * 把群聊消息中的 IMAGE/FILE 附件转换为多模态输入。
     *
     * @param actor        当前用户 ID（附件鉴权身份）
     * @param projectId    项目 ID
     * @param conversation 群聊消息列表
     * @return 转换结果：图片媒体 + 附加的内联文本
     */
    public Result load(UUID actor, UUID projectId, List<ContextMessage> conversation) {
        List<Media> media = new ArrayList<>();
        StringBuilder extraText = new StringBuilder();
        if (actor == null || projectId == null || conversation == null) {
            return new Result(media, extraText.toString());
        }
        for (ContextMessage message : conversation) {
            if (message == null || message.getAttachmentId() == null || message.getAttachmentId().isBlank()) {
                continue;
            }
            if ("IMAGE".equals(message.getType())) {
                attachImage(actor, projectId, message, media);
            } else if ("FILE".equals(message.getType())) {
                appendFileText(actor, projectId, message, extraText);
            }
        }
        return new Result(media, extraText.toString());
    }

    private void attachImage(UUID actor, UUID projectId, ContextMessage message, List<Media> media) {
        try {
            UUID attachmentId = UUID.fromString(message.getAttachmentId());
            AttachmentService.AttachmentPreviewMetadata meta =
                    attachmentService.previewMetadata(actor, projectId, attachmentId);
            if (!"IMAGE".equals(meta.previewType()) || meta.sizeBytes() == null
                    || meta.sizeBytes() > IMAGE_BYTES_CAP) {
                log.warn("attachment image skipped attachment={} type={} size={} (exceeds budget or unsupported)",
                        attachmentId, meta.previewType(), meta.sizeBytes());
                return;
            }
            AttachmentService.AttachmentPreviewContent content =
                    attachmentService.previewContent(actor, projectId, attachmentId);
            byte[] bytes = content.bytes();
            if (bytes.length > IMAGE_BYTES_CAP) {
                log.warn("attachment image skipped attachment={} actualBytes={} (exceeds budget)",
                        attachmentId, bytes.length);
                return;
            }
            MimeType mime = resolveImageMimeType(content.mediaType());
            String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
            media.add(new Media(mime, URI.create(dataUri)));
            log.info("attachment image attached attachment={} bytes={} mime={}",
                    attachmentId, bytes.length, mime);
        } catch (ApiException e) {
            log.warn("attachment image unavailable attachment={} code={}", message.getAttachmentId(), e.code());
        } catch (RuntimeException e) {
            log.warn("attachment image read failed attachment={} category={}",
                    message.getAttachmentId(), e.getClass().getSimpleName());
        }
    }

    private void appendFileText(UUID actor, UUID projectId, ContextMessage message, StringBuilder extraText) {
        try {
            UUID attachmentId = UUID.fromString(message.getAttachmentId());
            AttachmentService.AttachmentPreviewMetadata meta =
                    attachmentService.previewMetadata(actor, projectId, attachmentId);
            if (!"TEXT".equals(meta.previewType()) && !"CODE".equals(meta.previewType())) {
                return;
            }
            if (meta.sizeBytes() == null || meta.sizeBytes() > FILE_TEXT_BYTES_CAP) {
                log.warn("attachment file skipped attachment={} size={} (exceeds text budget)",
                        attachmentId, meta.sizeBytes());
                return;
            }
            AttachmentService.AttachmentPreviewContent content =
                    attachmentService.previewContent(actor, projectId, attachmentId);
            if (content.bytes().length > FILE_TEXT_BYTES_CAP) {
                return;
            }
            String name = message.getFileName() == null || message.getFileName().isBlank()
                    ? attachmentId.toString() : message.getFileName();
            extraText.append("\n\n[附件内容: ").append(name).append("]\n")
                    .append(new String(content.bytes(), StandardCharsets.UTF_8));
            log.info("attachment file attached attachment={} bytes={}", attachmentId, content.bytes().length);
        } catch (ApiException e) {
            log.warn("attachment file unavailable attachment={} code={}", message.getAttachmentId(), e.code());
        } catch (RuntimeException e) {
            log.warn("attachment file read failed attachment={} category={}",
                    message.getAttachmentId(), e.getClass().getSimpleName());
        }
    }

    /**
     * 把附件 mediaType 解析为合法 {@link MimeType}；缺失或非法时回退 {@code image/png}（预览类型
     * 已判定为图片，回退不会改变语义）。
     */
    private MimeType resolveImageMimeType(String mediaType) {
        if (mediaType != null && !mediaType.isBlank()) {
            try {
                return MimeTypeUtils.parseMimeType(mediaType.trim().toLowerCase(Locale.ROOT));
            } catch (InvalidMimeTypeException e) {
                // fall through 到默认
            }
        }
        return MimeType.valueOf("image/png");
    }

    /**
     * 附件转换结果：图片媒体列表 + 内联的文本/代码附件内容。
     */
    public record Result(List<Media> media, String extraText) {
        public Result(List<Media> media, String extraText) {
            this.media = media == null ? List.of() : media;
            this.extraText = extraText == null ? "" : extraText;
        }
    }
}