package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.UUID;

@Data
@TableName("projects")
public class ProjectEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID teamId;
    private UUID createdBy;
    private String name;
    private String description;
    private String status;
}
