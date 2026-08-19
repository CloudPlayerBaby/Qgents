package qg.qgent.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 项目更新请求（PATCH 语义：所有字段可选，传 null 表示保留原值）。
 */
@Data
public class UpdateProjectRequest {
    /**
     * 项目名称（可选）：传 null 表示保留原值；非 null 时去除首尾空白后覆盖保存，最长 255 字符。
     */
    @Size(max = 255)
    private String name;
    /**
     * 项目说明（可选）：传 null 表示保留原值；非 null 时覆盖保存，最长 16000 字符。
     */
    @Size(max = 16000)
    private String description;
    /**
     * 项目头像 URL（可选）：传 null 表示保留原值，传空串表示清空头像。
     */
    @Size(max = 4096)
    private String avatarUrl;
}
