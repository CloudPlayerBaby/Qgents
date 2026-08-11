package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MeResponse {
    private UserResponse user;
    private List<TeamResponse> teams;
    private List<ProjectResponse> projects;
}
