package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateTeamRequest {
    @NotBlank @Size(max = 255)
    private String name;
    /**
     * 团队简介（可选）：传 null 表示保留原值，传空串表示清空；非 null 时覆盖保存，
     * 最长为 2000 字符。
     */
    @Size(max = 2000)
    private String description;
}
