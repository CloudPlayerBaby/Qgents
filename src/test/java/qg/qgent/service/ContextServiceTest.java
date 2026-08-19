package qg.qgent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.dto.ContextRepository;
import qg.qgent.dto.ContextSkill;
import qg.qgent.dto.ContextMessage;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.MessageEntity;
import qg.qgent.mapper.MemoryMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.RequirementGroupRepositoryMapper;
import qg.qgent.mapper.SkillMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 群上下文必须遵循需求群成员可见性，不能绕过消息接口的访问校验。
 */
class ContextServiceTest {

    private final RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final SkillMapper skills = mock(SkillMapper.class);
    private final MemoryMapper memories = mock(MemoryMapper.class);
    private final RequirementGroupRepositoryMapper groupRepositories = mock(RequirementGroupRepositoryMapper.class);
    private final ProjectRepositoryMapper projectRepositories = mock(ProjectRepositoryMapper.class);
    private final GitHubRepositoryMapper githubRepositories = mock(GitHubRepositoryMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GroupService groupService = mock(GroupService.class);
    private final ContextService service = new ContextService(groups, messages, skills, memories, groupRepositories,
            access, groupService, new ObjectMapper(), projectRepositories, githubRepositories);

    private final UUID actor = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @Test
    void buildForGroupRejectsNonMemberBeforeReadingContent() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "GROUP_MEMBER_REQUIRED", "你不是该需求群成员，无法访问"))
                .when(groupService).requireGroupMember(projectId, groupId, actor);

        assertThatThrownBy(() -> service.buildForGroup(actor, projectId, groupId, 20))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("GROUP_MEMBER_REQUIRED"));
        verify(groups, never()).selectById(any());
        verify(messages, never()).selectList(any());
    }

    @Test
    void buildForGroupUsesSkillCatalogWithoutReadingSkillBodies() {
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setName("需求群");
        when(groups.selectById(groupId)).thenReturn(group);
        when(messages.selectList(any())).thenReturn(List.of());
        when(skills.listPublishedCatalog(projectId, actor))
                .thenReturn(List.of(new ContextSkill(UUID.randomUUID(), "数据库迁移")));
        when(memories.listMemories(any(), any(), anyBoolean(), any(), any())).thenReturn(List.of());
        when(groupRepositories.selectRepositoryIds(groupId)).thenReturn(List.of());

        assertThat(service.buildForGroup(actor, projectId, groupId, 50).getSkills())
                .extracting(ContextSkill::getName).containsExactly("数据库迁移");
        verify(skills).listPublishedCatalog(projectId, actor);
        verify(skills, never()).listSkills(any(), any(), any(), any());
    }

    @Test
    void buildForGroupResolvesRepositoryManifestForAgentContext() {
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setName("需求群");
        UUID bindingId = UUID.randomUUID();
        UUID githubId = UUID.randomUUID();
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(bindingId);
        binding.setProjectId(projectId);
        binding.setRepositoryId(githubId);
        binding.setDisplayName("前端仓库");
        binding.setDefaultBranch("develop");
        GitHubRepositoryEntity remote = new GitHubRepositoryEntity();
        remote.setId(githubId);
        remote.setOwnerLogin("example");
        remote.setName("frontend");

        when(groups.selectById(groupId)).thenReturn(group);
        when(messages.selectList(any())).thenReturn(List.of());
        when(skills.listPublishedCatalog(projectId, actor)).thenReturn(List.of());
        when(memories.listMemories(any(), any(), anyBoolean(), any(), any())).thenReturn(List.of());
        when(groupRepositories.selectRepositoryIds(groupId)).thenReturn(List.of(bindingId));
        when(projectRepositories.selectBatchIds(List.of(bindingId))).thenReturn(List.of(binding));
        when(githubRepositories.selectBatchIds(List.of(githubId))).thenReturn(List.of(remote));

        List<ContextRepository> repositories = service.buildForGroup(actor, projectId, groupId, 50).getRepositories();

        assertThat(repositories).hasSize(1);
        assertThat(repositories.get(0).getRepositoryId()).isEqualTo(bindingId.toString());
        assertThat(repositories.get(0).getName()).isEqualTo("前端仓库");
        assertThat(repositories.get(0).getFullName()).isEqualTo("example/frontend");
        assertThat(repositories.get(0).getDefaultBranch()).isEqualTo("develop");
    }

    @Test
    void chatHistorySearchRejectsNonMemberBeforeReadingMessages() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "GROUP_MEMBER_REQUIRED", "你不是该需求群成员，无法访问"))
                .when(groupService).requireGroupMember(projectId, groupId, actor);

        assertThatThrownBy(() -> service.searchChatHistory(actor, projectId, groupId, "登录", 20))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("GROUP_MEMBER_REQUIRED"));
        verify(messages, never()).searchByQuery(any(), any(), any(), anyInt());
    }

    @Test
    void chatHistorySearchIsStrictlyLimitedToSpecifiedGroup() {
        when(messages.searchByQuery(projectId, List.of(groupId), "登录", 20)).thenReturn(List.of());

        assertThat(service.searchChatHistory(actor, projectId, groupId, "登录", 20)).isEmpty();
        verify(messages).searchByQuery(projectId, List.of(groupId), "登录", 20);
    }

    @Test
    void chatHistorySearchRejectsBlankQueryWithoutMessageQuery() {
        assertThatThrownBy(() -> service.searchChatHistory(actor, projectId, groupId, " ", 20))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CHAT_SEARCH_QUERY_REQUIRED"));
        verify(messages, never()).searchByQuery(any(), any(), any(), anyInt());
    }

    @Test
    void taskSnapshotAddsOlderTriggerMessageWithoutTruncatingItsText() {
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setName("需求群");
        UUID triggerId = UUID.randomUUID();
        MessageEntity trigger = new MessageEntity();
        trigger.setId(triggerId);
        trigger.setRequirementGroupId(groupId);
        trigger.setSequenceNo(1L);
        trigger.setMessageType("TEXT");
        trigger.setAuthorUserId(actor);
        trigger.setContent("{\"text\":\"完整触发需求：需要支持历史导出\"}");
        when(groups.selectById(groupId)).thenReturn(group);
        when(messages.selectList(any())).thenReturn(List.of());
        when(messages.selectById(triggerId)).thenReturn(trigger);
        when(skills.listPublishedCatalog(projectId, actor)).thenReturn(List.of());
        when(memories.listMemories(any(), any(), anyBoolean(), any(), any())).thenReturn(List.of());
        when(groupRepositories.selectRepositoryIds(groupId)).thenReturn(List.of());

        assertThat(service.buildTaskSnapshot(actor, projectId, groupId, triggerId).getConversation())
                .extracting(ContextMessage::getText).containsExactly("完整触发需求：需要支持历史导出");
    }
}
