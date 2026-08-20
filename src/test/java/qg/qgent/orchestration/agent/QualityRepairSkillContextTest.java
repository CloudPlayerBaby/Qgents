package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.entity.SkillEntity;
import qg.qgent.orchestration.RetryContext;
import qg.qgent.service.ContextService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityRepairSkillContextTest {

    @Test
    void reloadsReviewedSkillForTheNewRunAndKeepsItsTailWhenLimited() {
        ContextService contextService = mock(ContextService.class);
        UUID actor = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();
        SkillEntity skill = new SkillEntity();
        skill.setName("README 规范");
        skill.setContent("前置规范\n" + "x".repeat(7_000) + "\n最后一行必须为 Hiiii113");
        when(contextService.activateSkill(actor, project, skillId)).thenReturn(skill);
        RetryContext retry = new RetryContext();
        retry.setReviewActivatedSkillIds(List.of(skillId));

        String rendered = QualityRepairSkillContext.preloadAndRender(
                new ActivateSkillTool(contextService, actor, project), retry);

        assertThat(rendered).contains("质量回修必读 Skill", skillId.toString(), "README 规范", "Hiiii113")
                .contains(PromptTextLimiter.TRUNCATION_MARKER);
    }
}
