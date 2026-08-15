package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 团队最近动态条目（聚合自项目事件，覆盖最近 24 小时保留窗口）。
 * type 枚举：TASK_COMPLETED/TASK_FAILED/DIFF_CREATED/MR_CREATED/MR_MERGED/TEST_RUN_FAILED。
 * 按前端约定后端只回 target.type + target.id，link 由前端自行拼前端路由；
 * actor 无可靠来源（MR 事件、无 task 关联的测试失败）时为 null。
 * createdAt 为 ISO8601 UTC 时间字符串。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityResponse {
    private String id;
    private String type;
    private String title;
    private String summary;
    private ActivityActor actor;
    private ActivityTarget target;
    private String link;
    private String createdAt;

    /**
     * 动态发起者（用户）；avatar 当前无来源，恒为 null。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityActor {
        private String id;
        private String displayName;
        private String avatar;
    }

    /**
     * 动态关联目标；type 取值 GROUP/TASK/MR/PROJECT/DIFF，title 为展示用标题。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityTarget {
        private String type;
        private String id;
        private String title;
    }
}
