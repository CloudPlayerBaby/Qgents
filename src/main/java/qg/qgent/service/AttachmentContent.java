package qg.qgent.service;

import java.io.InputStream;

/**
 * 附件内容流式读取结果（服务层 → 控制层内部传递）。
 * <p>
 * 仅用于鉴权后的内容下载代理（GET /attachments/{id}/content），不做 JSON 序列化；
 * 调用方负责关闭 {@link InputStream}。
 */
public class AttachmentContent {

    private final InputStream stream;
    private final String contentType;
    private final String fileName;
    private final Long sizeBytes;

    public AttachmentContent(InputStream stream, String contentType, String fileName, Long sizeBytes) {
        this.stream = stream;
        this.contentType = contentType;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
    }

    public InputStream getStream() {
        return stream;
    }

    public String getContentType() {
        return contentType;
    }

    public String getFileName() {
        return fileName;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }
}
