package qg.qgent.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class ProjectMembershipView {
    private UUID id;
    private UUID teamId;
    private String name;
    private String description;
    private String role;
    private String status;
}
