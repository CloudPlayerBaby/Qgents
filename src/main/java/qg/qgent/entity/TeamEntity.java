package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.UUID;

@Data
@TableName("teams")
public class TeamEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID ownerUserId;
    private String name;
    private String status;
}
