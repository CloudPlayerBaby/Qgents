package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
@TableName(value = "projects", autoResultMap = true)
public class ProjectEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID teamId;
    private UUID createdBy;
    private String name;
    private String description;
    /**
     * 项目头像 URL（OSS 公共读长期地址，可为空；由项目头像上传确认后写入）。
     */
    private String avatarUrl;

    /**
     * 项目设置 JSON：需求群规则开关（allowCreateGroup/autoArchiveGroup/allowAgentTrigger/autoJoinAllGroups）；
     * 空或缺失时使用默认值。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> settings;

    private String status;
}
