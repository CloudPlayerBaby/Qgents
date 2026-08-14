package qg.qgent.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 团队视图。除基础信息外补充前端卡片展示所需字段：
 * memberCount（成员数）、description（团队简介）、createdAt（创建时间）。
 * 保留 (id, name, role) 三参构造函数兼容既有调用，其余字段通过 setter 填充。
 */
@Data
@NoArgsConstructor
public class TeamResponse {
    private String id;
    private String name;
    private String role;
    /** 团队成员数，用于前端团队卡片展示。 */
    private Integer memberCount;
    /** 团队简介，可为空。 */
    private String description;
    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    public TeamResponse(String id, String name, String role) {
        this.id = id;
        this.name = name;
        this.role = role;
    }
}
