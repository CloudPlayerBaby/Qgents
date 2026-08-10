package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.UUID;

@Data
@TableName("project_members")
public class ProjectMemberEntity {
    private UUID projectId;
    private UUID userId;
    private String role;
}
