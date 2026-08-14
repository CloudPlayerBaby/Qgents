package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTeamRequest {
    @NotBlank @Size(max = 255)
    private String name;
    /** 团队简介（可选），最长为 2000 字符。 */
    @Size(max = 2000)
    private String description;
}
