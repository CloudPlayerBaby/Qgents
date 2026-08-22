package qg.qgent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.dto.ContextRepository;
import qg.qgent.dto.ContextSkill;
import qg.qgent.dto.ContextMessage;
import qg.qgent.dto.GroupContext;
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
        binding.setStatus("ACTIVE");
        GitHubRepositoryEntity remote = new GitHubRepositoryEntity();
        remote.setId(githubId);
        remote.setOwnerLogin("example");
        remote.setName("frontend");

        when(groups.selectById(groupId)).thenReturn(group);
        when(messages.selectList(any())).thenReturn(List.of());
        when(skills.listPublishedCatalog(projectId, actor)).thenReturn(List.of());
        when(memories.listMemories(any(), any(), anyBoolean(), any(), any())).thenReturn(List.of());
        when(projectRepositories.selectList(any())).thenReturn(List.of(binding));
        when(projectRepositories.selectBatchIds(List.of(bindingId))).thenReturn(List.of(binding));
        when(githubRepositories.selectBatchIds(List.of(githubId))).thenReturn(List.of(remote));

        List<ContextRepository> repositories = service.buildForGroup(actor, projectId, groupId, 50).getRepositories();

        assertThat(repositories).hasSize(1);
        assertThat(repositories.get(0).getRepositoryId()).isEqualTo(bindingId.toString());
        assertThat(repositories.get(0).getName()).isEqualTo("前端仓库");
        assertThat(repositories.get(0).getFullName()).isEqualTo("example/frontend");
        assertThat(repositories.get(0).getDefaultBranch()).isEqualTo("develop");
        verify(groupRepositories, never()).selectRepositoryIds(groupId);
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

    @Test
    void taskSnapshotUsesExplicitRepositoryIdsWhenGroupHasNoBindings() {
        // 复现线上场景：需求群未绑定仓库，手动触发选择仓库时 Workspace 正确挂载、AI 上下文仓库为 0。
        UUID manual = UUID.randomUUID();
        UUID githubManual = UUID.randomUUID();
        stubBaseContext();
        when(groupRepositories.selectRepositoryIds(groupId)).thenReturn(List.of());
        when(projectRepositories.selectBatchIds(List.of(manual)))
                .thenReturn(List.of(binding(manual, projectId, githubManual, "手动选择仓库", "main")));
        when(githubRepositories.selectBatchIds(List.of(githubManual)))
                .thenReturn(List.of(remote(githubManual, "example", "manual")));

        GroupContext snapshot = service.buildTaskSnapshot(actor, projectId, groupId, null, List.of(manual));

        assertThat(snapshot.getRepositoryIds()).containsExactly(manual.toString());
        assertThat(snapshot.getRepositories()).extracting(ContextRepository::getName)
                .containsExactly("手动选择仓库");
        assertThat(snapshot.getRepositories().get(0).getFullName()).isEqualTo("example/manual");
        assertThat(snapshot.getRepositories().get(0).getDefaultBranch()).isEqualTo("main");
    }

    @Test
    void taskSnapshotExplicitRepositoryIdsOverrideGroupBindings() {
        // 手动选择的仓库与需求群绑定不同：快照必须只以显式 ID 为准，不复读群绑定记录。
        UUID groupBound = UUID.randomUUID();
        UUID manual = UUID.randomUUID();
        UUID githubGroup = UUID.randomUUID();
        UUID githubManual = UUID.randomUUID();
        stubBaseContext();
        when(groupRepositories.selectRepositoryIds(groupId)).thenReturn(List.of(groupBound));
        when(projectRepositories.selectBatchIds(List.of(groupBound)))
                .thenReturn(List.of(binding(groupBound, projectId, githubGroup, "群绑定仓库", "develop")));
        when(githubRepositories.selectBatchIds(List.of(githubGroup)))
                .thenReturn(List.of(remote(githubGroup, "example", "group")));
        when(projectRepositories.selectBatchIds(List.of(manual)))
                .thenReturn(List.of(binding(manual, projectId, githubManual, "手动选择仓库", "main")));
        when(githubRepositories.selectBatchIds(List.of(githubManual)))
                .thenReturn(List.of(remote(githubManual, "example", "manual")));

        GroupContext snapshot = service.buildTaskSnapshot(actor, projectId, groupId, null, List.of(manual));

        assertThat(snapshot.getRepositoryIds()).containsExactly(manual.toString());
        assertThat(snapshot.getRepositories()).extracting(ContextRepository::getName)
                .containsExactly("手动选择仓库");
    }

    @Test
    void taskSnapshotWithoutExplicitRepositoryIdsUsesProjectRepositories() {
        UUID groupBound = UUID.randomUUID();
        UUID githubGroup = UUID.randomUUID();
        stubBaseContext();
        ProjectRepositoryEntity binding = binding(groupBound, projectId, githubGroup, "项目仓库", "develop");
        binding.setStatus("ACTIVE");
        when(projectRepositories.selectList(any())).thenReturn(List.of(binding));
        when(projectRepositories.selectBatchIds(List.of(groupBound)))
                .thenReturn(List.of(binding));
        when(githubRepositories.selectBatchIds(List.of(githubGroup)))
                .thenReturn(List.of(remote(githubGroup, "example", "group")));

        GroupContext snapshot = service.buildTaskSnapshot(actor, projectId, groupId, null);

        assertThat(snapshot.getRepositoryIds()).containsExactly(groupBound.toString());
        assertThat(snapshot.getRepositories()).extracting(ContextRepository::getName)
                .containsExactly("项目仓库");
    }

    private void stubBaseContext() {
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setName("需求群");
        when(groups.selectById(groupId)).thenReturn(group);
        when(messages.selectList(any())).thenReturn(List.of());
        when(skills.listPublishedCatalog(projectId, actor)).thenReturn(List.of());
        when(memories.listMemories(any(), any(), anyBoolean(), any(), any())).thenReturn(List.of());
    }

    private ProjectRepositoryEntity binding(UUID id, UUID projectId, UUID githubId, String displayName, String defaultBranch) {
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(id);
        binding.setProjectId(projectId);
        binding.setRepositoryId(githubId);
        binding.setDisplayName(displayName);
        binding.setDefaultBranch(defaultBranch);
        return binding;
    }

    private GitHubRepositoryEntity remote(UUID id, String owner, String name) {
        GitHubRepositoryEntity remote = new GitHubRepositoryEntity();
        remote.setId(id);
        remote.setOwnerLogin(owner);
        remote.setName(name);
        return remote;
    }
}
