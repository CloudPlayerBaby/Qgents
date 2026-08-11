package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeamInvitationResponse {
    private String id;
    private String email;
    private String status;
    private LocalDateTime expiresAt;
}
