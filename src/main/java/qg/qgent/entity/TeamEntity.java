package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@TableName("teams")
public class TeamEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID ownerUserId;
    private String name;
    /**
     * 团队简介，可为空；由 Team Owner 在创建/修改团队时设置。
     */
    private String description;
    private String status;
    /**
     * 创建时间（UTC），由数据库 DEFAULT CURRENT_TIMESTAMP(6) 生成。
     */
    private LocalDateTime createdAt;
}
