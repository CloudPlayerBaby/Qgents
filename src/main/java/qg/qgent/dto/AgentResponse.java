package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Public Agent card returned by team Agent endpoints.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {
    @Schema(description = "Agent ID")
    private String id;
    private String name;
    private String avatar;
    private String role;
    private List<String> capabilities;
    private String prompt;
    private String visibility;
    private String status;
    private String createdBy;
}
