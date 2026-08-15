package qg.qgent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为前端公开接口补充统一的中文分组、接口编号和简明说明。
 * <p>
 * 编号与接口文档章节一致（§4、§5.1、§5.2、§6、§6.1、§7、§7.1、§8、§9、§11.1.1、§11.3、§12.1~§12.4、§13），
 * 供 Apifox 导入和前后端沟通使用；{@code /internal/**} 是服务间接口，刻意不在此处处理。
 */
@Configuration
public class FrontendOpenApiConfiguration {

    @Bean
    OpenApiCustomizer frontendApiChineseDocumentation() {
        return openApi -> documentPublicPaths(openApi);
    }

    private void documentPublicPaths(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        // 默认 Springdoc 会扫描所有 Controller。前端导入入口只允许看到 /api/v1，
        // 不能把服务间凭据、Worker 等 /internal 协议暴露到 Apifox。
        openApi.getPaths().keySet().removeIf(path -> !path.startsWith("/api/v1/"));
        Map<String, Integer> counters = new LinkedHashMap<>();
        openApi.getPaths().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("/api/v1/"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entry.getValue().readOperationsMap().forEach((method, operation) -> {
                    ApiGroup group = group(entry.getKey());
                    int sequence = counters.merge(group.code(), 1, Integer::sum);
                    String id = "API-" + group.code() + "-" + String.format("%02d", sequence);
                    operation.setTags(List.of(group.name()));
                    operation.setSummary(id + " " + summary(entry.getKey(), method));
                    String existing = operation.getDescription();
                    String prefix = "前端公开接口。编号：" + id + "。";
                    operation.setDescription(existing == null || existing.isBlank() ? prefix : prefix + "\n\n" + existing);
                }));
        openApi.setTags(List.of(
                tag("4 认证与账户", "4"), tag("5.1 团队与邀请", "5.1"),
                tag("5.2 项目与项目成员", "5.2"), tag("6 GitHub 集成与仓库", "6"),
                tag("6.1 分支策略与质量门禁", "6.1"), tag("7 群组与消息", "7"),
                tag("7.1 通知中心", "7.1"), tag("8 共享 Skill", "8"),
                tag("9 共享 Memory", "9"), tag("11.1.1 Agent 技能绑定", "11.1.1"),
                tag("11.3 任务与执行计划", "11.3"), tag("12.1 实时事件流", "12.1"),
                tag("12.2 任务运行与执行上下文", "12.2"), tag("12.3 Diff 与审查", "12.3"),
                tag("12.4 测试与预演", "12.4"), tag("13 合并请求", "13")));
    }

    private Tag tag(String name, String code) {
        return new Tag().name(name).description("接口编号段：API-" + code + "-xx");
    }

    private ApiGroup group(String path) {
        if (path.contains("/events")) return new ApiGroup("12.1", "实时事件流");
        if (path.startsWith("/api/v1/notifications")) return new ApiGroup("7.1", "通知中心");
        if (path.contains("/agent-skill-bindings")) return new ApiGroup("11.1.1", "Agent 技能绑定");
        if (path.contains("/branch-policies") || path.contains("/quality-gates"))
            return new ApiGroup("6.1", "分支策略与质量门禁");
        if (path.contains("/task-runs")) return new ApiGroup("12.2", "任务运行与执行上下文");
        if (path.contains("/test-runs") || path.contains("/dry-runs")) return new ApiGroup("12.4", "测试与预演");
        if (path.contains("/diffs")) return new ApiGroup("12.3", "Diff 与审查");
        if (path.contains("/merge-requests")) return new ApiGroup("13", "合并请求");
        if (path.contains("/integrations/github") || path.contains("/repositories"))
            return new ApiGroup("6", "GitHub 集成与仓库");
        if (path.contains("/tasks")) return new ApiGroup("11.3", "任务与执行计划");
        if (path.contains("/groups") || path.contains("/messages") || path.contains("/attachments")
                || path.contains("/context")) return new ApiGroup("7", "群组与消息");
        if (path.contains("/skills")) return new ApiGroup("8", "共享 Skill");
        if (path.contains("/memories")) return new ApiGroup("9", "共享 Memory");
        if (path.contains("/auth/") || path.equals("/api/v1/me")) return new ApiGroup("4", "认证与账户");
        if (path.contains("/projects")) return new ApiGroup("5.2", "项目与项目成员");
        return new ApiGroup("5.1", "团队与邀请");
    }

    private String summary(String path, PathItem.HttpMethod method) {
        if (path.endsWith("/register")) return "注册账号";
        if (path.endsWith("/login")) return "登录";
        if (path.endsWith("/refresh")) return "刷新登录令牌";
        if (path.endsWith("/logout")) return "退出登录";
        if (path.endsWith("/password-reset-requests")) return "提交密码重置申请";
        if (path.endsWith("/password-resets")) return "重置密码";
        if (path.equals("/api/v1/me"))
            return method == PathItem.HttpMethod.GET ? "获取当前用户资料" : "修改当前用户资料";
        if (path.endsWith("/trigger-task")) return "从消息触发任务";
        if (path.endsWith("/replace-agent")) return "更换步骤 Agent";
        if (path.endsWith("/cq-approvals")) return "提交 CQ+1 审查";
        if (path.endsWith("/cq-rejections")) return "拒绝 CQ 审查";
        if (path.endsWith("/steps")) return "写入任务步骤计划";
        if (path.endsWith("/drafts")) return "生成 AI 草稿";
        if (path.endsWith("/read-all")) return "全部通知已读";
        if (path.endsWith("/read")) return "标记通知已读";
        if (path.endsWith("/execution-context")) return "获取执行上下文";
        if (path.endsWith("/logs")) return "读取执行日志";
        if (path.endsWith("/files")) return "查询" + resource(path) + "文件";
        if (path.endsWith("/checks")) return "查询门禁检查";
        if (path.endsWith("/reviews")) return "查询审查摘要";
        if (path.endsWith("/report")) return "获取" + resource(path) + "报告";
        if (path.contains("/input-requests")) {
            if (path.endsWith("/reply")) return "回复输入请求";
            if (path.endsWith("/approve")) return "批准审批请求";
            if (path.endsWith("/reject")) return "拒绝审批请求";
            return "查询输入请求";
        }
        String resource = resource(path);
        if (method == PathItem.HttpMethod.GET) return "查询" + resource;
        if (method == PathItem.HttpMethod.PUT) {
            if (path.contains("branch-policies") || path.contains("quality-gates")) return "配置" + resource;
            return "完整更新" + resource;
        }
        return switch (method) {
            case POST -> action(path, resource);
            case PATCH -> "修改" + resource;
            case DELETE -> "删除" + resource;
            default -> "操作" + resource;
        };
    }

    private String action(String path, String resource) {
        if (path.endsWith("/accept")) return "接受" + resource;
        if (path.endsWith("/reject")) return "拒绝" + resource;
        if (path.endsWith("/approve")) return "批准" + resource;
        if (path.endsWith("/cancel")) return "取消" + resource;
        if (path.endsWith("/archive")) return "归档" + resource;
        if (path.endsWith("/restore")) return "恢复" + resource;
        if (path.endsWith("/merge")) return "合并" + resource;
        if (path.endsWith("/sync")) return "同步" + resource;
        if (path.endsWith("/retry")) return "重试" + resource;
        if (path.endsWith("/leave")) return "退出" + resource;
        if (path.endsWith("/submit-review")) return "提交" + resource + "审核";
        return "创建" + resource;
    }

    private String resource(String path) {
        if (path.contains("agent-skill-bindings")) return "Agent 技能绑定";
        if (path.contains("attachments")) return "附件直传凭证";
        if (path.contains("branch-policies")) return "分支策略";
        if (path.contains("quality-gates")) return "质量门禁";
        if (path.contains("context")) return "群聊上下文";
        if (path.contains("diffs")) return "Diff";
        if (path.contains("events")) return "项目事件流";
        if (path.contains("integrations/github/installations")) return "GitHub 安装授权";
        if (path.contains("repositories")) return "仓库绑定";
        if (path.contains("groups") && path.contains("members")) return "群成员";
        if (path.contains("invitations")) return "团队邀请";
        if (path.contains("members") && path.contains("/teams/")) return "团队成员";
        if (path.contains("members") && path.contains("/projects/")) return "项目成员";
        if (path.contains("members")) return "成员";
        if (path.contains("groups")) return "群组";
        if (path.contains("memories")) return "Memory";
        if (path.contains("merge-requests")) return "合并请求";
        if (path.contains("messages")) return "群消息";
        if (path.contains("skills")) return "Skill";
        if (path.contains("task-runs")) return "任务运行";
        if (path.contains("tasks")) return "任务";
        if (path.contains("test-runs")) return "测试运行";
        if (path.contains("dry-runs")) return "预演运行";
        if (path.contains("notifications")) return "通知";
        if (path.contains("team-invitations")) return "团队邀请";
        if (path.contains("teams")) return "团队";
        if (path.contains("projects")) return "项目";
        return "资源";
    }

    private record ApiGroup(String code, String name) {
    }
}
