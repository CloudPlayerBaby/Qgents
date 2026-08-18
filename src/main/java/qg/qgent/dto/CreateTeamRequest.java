package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTeamRequest {
    @NotBlank
    @Size(max = 255)
    private String name;
    /**
     * 团队简介（可选），最长为 2000 字符。
     */
    @Size(max = 2000)
    private String description;
    /**
     * 团队头像 URL（可选）：由团队头像上传 confirm 返回的公共读 URL，随创建提交。
     */
    @Size(max = 4096)
    private String avatarUrl;
}
