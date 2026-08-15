package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 附件状态响应（确认上传后返回）：客户端上传到对象存储后调用确认接口，服务端校验对象存在并置 READY。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentStatusResponse {

    /**
     * 附件 ID（UUIDv7，字符串形式）。
     */
    @Schema(description = "附件 ID")
    private String attachmentId;

    /**
     * 附件状态：PENDING/READY/FAILED/DELETED。
     */
    @Schema(description = "附件状态")
    private String status;
}
