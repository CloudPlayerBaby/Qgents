package qg.qgent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import qg.qgent.api.ApiException;
import qg.qgent.dto.BranchPolicyDto;
import qg.qgent.dto.QualityGateDto;
import qg.qgent.dto.UpdateBranchPolicyRequest;
import qg.qgent.dto.UpdateQualityGateRequest;
import qg.qgent.entity.BranchPolicyJson;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RepositoryBranchConfigEntity;
import qg.qgent.entity.RepositoryBranchConfigTestsetEntity;
import qg.qgent.entity.TestsetEntity;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.RepositoryBranchConfigMapper;
import qg.qgent.mapper.RepositoryBranchConfigTestsetMapper;
import qg.qgent.mapper.TeamMemberMapper;
import qg.qgent.mapper.TestsetMapper;

@ExtendWith(MockitoExtension.class)
class RepositoryBranchConfigServiceTest {
    private final UUID actorId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID projectRepositoryId = UUID.randomUUID();
    private final String branchName = "main";

    @Mock private RepositoryBranchConfigMapper branchConfigMapper;
    @Mock private RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper;
    @Mock private ProjectRepositoryMapper projectRepositoryMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private TeamMemberMapper teamMemberMapper;
    @Mock private TestsetMapper testsetMapper;
    @Mock private GitStoreSyncService gitStores;

    private RepositoryBranchConfigService service;

    @BeforeAll
    static void initializeMyBatisPlusMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, qg.qgent.handler.UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "RepositoryBranchConfigServiceTest");
        TableInfoHelper.initTableInfo(assistant, RepositoryBranchConfigEntity.class);
        TableInfoHelper.initTableInfo(assistant, RepositoryBranchConfigTestsetEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new RepositoryBranchConfigService(branchConfigMapper, branchConfigTestsetMapper,
                projectRepositoryMapper, projectMapper, projectMemberMapper, teamMemberMapper, testsetMapper, gitStores);
        when(gitStores.normalizeTargetBranch(anyString())).thenAnswer(invocation -> invocation.getArgument(0, String.class).trim());
    }

    @Test
    void getsBranchPolicyWhenAuthorized() {
        authorizeProjectMember();
        when(projectRepositoryMapper.selectOne(any(Wrapper.class))).thenReturn(projectRepository());
        RepositoryBranchConfigEntity config = new RepositoryBranchConfigEntity();
        java.util.Map<String, Object> policy = new java.util.HashMap<>();
        policy.put("requirePullRequest", true);
        policy.put("allowDirectPush", false);
        config.setPolicyJson(policy);
        when(branchConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config);

        BranchPolicyDto result = service.getBranchPolicy(actorId, projectId, repositoryId, branchName);

        assertEquals(true, result.getRequirePullRequest());
        assertEquals(false, result.getAllowDirectPush());
    }

    @Test
    void rejectsGetBranchPolicyForNonMember() {
        ApiException exception = assertThrows(ApiException.class, 
                () -> service.getBranchPolicy(actorId, projectId, repositoryId, branchName));
        
        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        verify(branchConfigMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void updatesBranchPolicyWhenAdmin() {
        authorizeProjectAdmin();
        when(projectRepositoryMapper.selectOne(any(Wrapper.class))).thenReturn(projectRepository());
        when(branchConfigMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        UpdateBranchPolicyRequest request = new UpdateBranchPolicyRequest();
        request.setRequirePullRequest(true);
        request.setMinimumHumanApprovals(2);

        BranchPolicyDto result = service.updateBranchPolicy(actorId, projectId, repositoryId, branchName, request);

        assertEquals(true, result.getRequirePullRequest());
        assertEquals(2, result.getMinimumHumanApprovals());

        ArgumentCaptor<RepositoryBranchConfigEntity> captor = ArgumentCaptor.forClass(RepositoryBranchConfigEntity.class);
        verify(branchConfigMapper).insert(captor.capture());
        verify(branchConfigMapper).updateById(any(RepositoryBranchConfigEntity.class));
        assertEquals(branchName, captor.getValue().getBranchName());
    }

    @Test
    void rejectsUpdateBranchPolicyForNonAdmin() {
        authorizeProjectMember(); // Only member, not admin
        when(projectMemberMapper.selectCount(any(Wrapper.class))).thenReturn(0L); // Force admin check to fail
        
        UpdateBranchPolicyRequest request = new UpdateBranchPolicyRequest();
        
        ApiException exception = assertThrows(ApiException.class, 
                () -> service.updateBranchPolicy(actorId, projectId, repositoryId, branchName, request));
        
        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        verify(branchConfigMapper, never()).updateById(any(RepositoryBranchConfigEntity.class));
    }
    
    @Test
    void getsQualityGateWhenAuthorized() {
        authorizeProjectMember();
        when(projectRepositoryMapper.selectOne(any(Wrapper.class))).thenReturn(projectRepository());
        RepositoryBranchConfigEntity config = new RepositoryBranchConfigEntity();
        config.setId(UUID.randomUUID());
        config.setRequiredChecks(List.of("TESTSET", "AI_REVIEW"));
        when(branchConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config);
        
        RepositoryBranchConfigTestsetEntity ts = new RepositoryBranchConfigTestsetEntity();
        UUID tsId = UUID.randomUUID();
        ts.setTestsetId(tsId);
        org.mockito.Mockito.lenient().when(branchConfigTestsetMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(ts));

        QualityGateDto result = service.getQualityGate(actorId, projectId, repositoryId, branchName);

        assertNotNull(result.getRequiredChecks());
        assertEquals(2, result.getRequiredChecks().size());
        assertEquals(1, result.getRequiredTestsetIds().size());
        assertEquals(tsId, result.getRequiredTestsetIds().get(0));
    }

    @Test
    void updatesQualityGateWhenAdmin() {
        authorizeProjectAdmin();
        when(projectRepositoryMapper.selectOne(any(Wrapper.class))).thenReturn(projectRepository());
        RepositoryBranchConfigEntity config = new RepositoryBranchConfigEntity();
        config.setId(UUID.randomUUID());
        when(branchConfigMapper.selectOne(any(Wrapper.class))).thenReturn(config);

        UpdateQualityGateRequest request = new UpdateQualityGateRequest();
        request.setRequiredChecks(List.of("TESTSET"));
        UUID testsetId = UUID.randomUUID();
        request.setRequiredTestsetIds(List.of(testsetId));
        TestsetEntity testset = new TestsetEntity();
        testset.setId(testsetId); testset.setProjectId(projectId);
        testset.setProjectRepositoryId(projectRepositoryId); testset.setStatus("ENABLED");
        when(testsetMapper.selectBatchIds(List.of(testsetId))).thenReturn(List.of(testset));

        QualityGateDto result = service.updateQualityGate(actorId, projectId, repositoryId, branchName, request);

        assertEquals(1, result.getRequiredChecks().size());
        assertEquals(1, result.getRequiredTestsetIds().size());
        assertEquals(testsetId, result.getRequiredTestsetIds().get(0));

        verify(branchConfigMapper).updateById(config);
        verify(branchConfigTestsetMapper).delete(any(Wrapper.class));
        ArgumentCaptor<RepositoryBranchConfigTestsetEntity> tsCaptor = ArgumentCaptor.forClass(RepositoryBranchConfigTestsetEntity.class);
        verify(branchConfigTestsetMapper).insert(tsCaptor.capture());
        assertEquals(testsetId, tsCaptor.getValue().getTestsetId());
    }

    private void authorizeProjectMember() {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);
        org.mockito.Mockito.lenient().when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        org.mockito.Mockito.lenient().when(projectMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
    }
    
    private void authorizeProjectAdmin() {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);
        org.mockito.Mockito.lenient().when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        org.mockito.Mockito.lenient().when(projectMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
    }

    private ProjectRepositoryEntity projectRepository() {
        ProjectRepositoryEntity pr = new ProjectRepositoryEntity();
        pr.setId(projectRepositoryId);
        pr.setProjectId(projectId);
        pr.setRepositoryId(repositoryId);
        return pr;
    }
}
