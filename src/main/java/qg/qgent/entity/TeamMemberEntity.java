package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.UUID;

@Data
@TableName("team_members")
public class TeamMemberEntity {
    private UUID teamId;
    private UUID userId;
    private String role;
}
