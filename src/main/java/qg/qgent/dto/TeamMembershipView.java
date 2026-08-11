package qg.qgent.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class TeamMembershipView {
    private UUID id;
    private UUID ownerUserId;
    private String name;
    private String role;
}
