package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@TableName("team_invitations")
public class TeamInvitationEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID teamId;
    private UUID invitedBy;
    private String emailNormalized;
    private byte[] tokenHash;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime createdAt;
}
