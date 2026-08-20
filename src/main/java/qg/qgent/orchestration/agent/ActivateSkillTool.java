package qg.qgent.orchestration.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.entity.SkillEntity;
import qg.qgent.service.ContextService;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 运行时 Skill 激活工具。
 * <p>
 * 每个实例只服务一条 TaskRun，因此已激活 Skill 集合不会跨运行继承。完整正文仅作为本次
 * 原生工具调用的返回内容、或质量回修运行受控注入的正文进入模型历史，不写入默认上下文或日志。
 */
@Slf4j
public class ActivateSkillTool {

    private static final int MAX_ACTIVATIONS = 5;

    private final ContextService contextService;
    private final UUID actor;
    private final UUID projectId;
    private final Set<UUID> activatedSkillIds = new LinkedHashSet<>();

    public ActivateSkillTool(ContextService contextService, UUID actor, UUID projectId) {
        this.contextService = contextService;
        this.actor = actor;
        this.projectId = projectId;
    }

    /**
     * 激活一条已发布且当前用户可见的项目 Skill。
     */
    @Tool(name = "activate_skill", description = "按 Skill 目录中的 skillId 激活完整 Skill 正文；"
            + "仅在当前上下文确实需要该操作规范时调用。每个 TaskRun 最多激活 5 个不同 Skill，"
            + "重复激活不会重复消耗预算")
    public Map<String, Object> activateSkill(
            @ToolParam(description = "默认上下文 Skill 目录中的 UUID skillId") String skillId) {
        Map<String, Object> result = new LinkedHashMap<>();
        UUID parsedSkillId;
        try {
            parsedSkillId = UUID.fromString(skillId == null ? "" : skillId.trim());
        } catch (IllegalArgumentException exception) {
            return error(result, "activate_skill 的 skillId 必须是 Skill 目录中的有效 UUID");
        }
        if (activatedSkillIds.contains(parsedSkillId)) {
            result.put("ok", true);
            result.put("alreadyActivated", true);
            result.put("skillId", parsedSkillId.toString());
            result.put("budget", "used " + activatedSkillIds.size() + "/" + MAX_ACTIVATIONS);
            log.info("skill activation repeated projectId={} skillId={} used={}", projectId, parsedSkillId,
                    activatedSkillIds.size());
            return result;
        }
        if (activatedSkillIds.size() >= MAX_ACTIVATIONS) {
            return error(result, "activate_skill 激活预算已用尽（已激活 " + activatedSkillIds.size() + "/"
                    + MAX_ACTIVATIONS + " 个不同 Skill），请基于当前上下文继续");
        }
        final SkillEntity skill;
        try {
            skill = contextService.activateSkill(actor, projectId, parsedSkillId);
        } catch (RuntimeException exception) {
            log.info("skill activation denied projectId={} skillId={} category={}", projectId, parsedSkillId,
                    exception.getClass().getSimpleName());
            return error(result, "activate_skill 失败: " + safeMessage(exception));
        }
        activatedSkillIds.add(parsedSkillId);
        result.put("ok", true);
        result.put("skillId", parsedSkillId.toString());
        result.put("name", skill.getName());
        result.put("content", skill.getContent());
        result.put("budget", "used " + activatedSkillIds.size() + "/" + MAX_ACTIVATIONS);
        log.info("skill activated projectId={} skillId={} used={}", projectId, parsedSkillId,
                activatedSkillIds.size());
        return result;
    }

    /**
     * 供质量回修的写 Agent 重新激活上一轮审查实际使用过的 Skill。
     * <p>
     * 这里只传递并重新校验 Skill ID，绝不跨 TaskRun 缓存或搬运正文；因此发布状态、项目归属和
     * 当前用户可见性仍由 {@link ContextService#activateSkill} 在本次运行中校验。预加载同样计入
     * 当前运行的 5 条预算，之后模型重复调用同一 Skill 不会重复消耗预算。
     */
    public List<ActivatedSkill> preloadForQualityRepair(Collection<UUID> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        List<ActivatedSkill> result = new java.util.ArrayList<>();
        for (UUID skillId : skillIds) {
            if (skillId == null || result.size() >= MAX_ACTIVATIONS) {
                continue;
            }
            Map<String, Object> activated = activateSkill(skillId.toString());
            if (!Boolean.TRUE.equals(activated.get("ok")) || Boolean.TRUE.equals(activated.get("alreadyActivated"))) {
                continue;
            }
            Object name = activated.get("name");
            Object content = activated.get("content");
            if (name instanceof String skillName && content instanceof String skillContent) {
                result.add(new ActivatedSkill(skillId, skillName, skillContent));
            }
        }
        return List.copyOf(result);
    }

    /** 当前运行已实际通过服务端校验并读取的 Skill ID，不包含正文。 */
    public List<UUID> activatedSkillIds() {
        return List.copyOf(activatedSkillIds);
    }

    /** 仅供当前模型上下文组装的已激活 Skill，不得持久化正文。 */
    public record ActivatedSkill(UUID skillId, String name, String content) {
    }

    private Map<String, Object> error(Map<String, Object> result, String message) {
        result.put("ok", false);
        result.put("error", message);
        return result;
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "skill unavailable";
        }
        String firstLine = throwable.getMessage().strip().lines().findFirst().orElse("skill unavailable");
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200);
    }
}
