package qg.qgent.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import qg.qgent.dto.BranchPolicyDto;
import qg.qgent.dto.QualityGateDto;
import qg.qgent.security.CurrentActorProvider;
import qg.qgent.service.RepositoryBranchConfigService;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 分支策略端点使用项目仓库绑定 ID，而非 GitHub 仓库镜像 ID。 */
class RepositoryBranchConfigControllerTest {
    private final RepositoryBranchConfigService service = mock(RepositoryBranchConfigService.class);
    private final CurrentActorProvider currentActor = mock(CurrentActorProvider.class);
    private final UUID actorId = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(currentActor.currentUserId()).thenReturn(actorId);
        mockMvc = MockMvcBuilders.standaloneSetup(new RepositoryBranchConfigController(service, currentActor)).build();
    }

    @Test
    void routesBranchPolicyWithProjectRepositoryId() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID projectRepositoryId = UUID.randomUUID();
        BranchPolicyDto response = new BranchPolicyDto();
        response.setRequirePullRequest(true);
        when(service.getBranchPolicy(actorId, projectId, projectRepositoryId, "main")).thenReturn(response);

        mockMvc.perform(get("/api/v1/projects/{projectId}/repositories/{projectRepositoryId}/branch-policies/{branch}",
                        projectId, projectRepositoryId, "main"))
                .andExpect(status().isOk());

        verify(service).getBranchPolicy(actorId, projectId, projectRepositoryId, "main");
    }

    @Test
    void routesQualityGateWithProjectRepositoryId() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID projectRepositoryId = UUID.randomUUID();
        QualityGateDto response = new QualityGateDto();
        response.setRequiredChecks(List.of("DRY_RUN", "CQ_PLUS_ONE"));
        when(service.updateQualityGate(eq(actorId), eq(projectId), eq(projectRepositoryId), eq("main"),
                org.mockito.ArgumentMatchers.any())).thenReturn(response);

        mockMvc.perform(put("/api/v1/projects/{projectId}/repositories/{projectRepositoryId}/quality-gates/{branch}",
                        projectId, projectRepositoryId, "main")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requiredChecks\":[\"DRY_RUN\",\"CQ_PLUS_ONE\"],\"requiredTestsetIds\":[]}"))
                .andExpect(status().isOk());

        verify(service).updateQualityGate(eq(actorId), eq(projectId), eq(projectRepositoryId), eq("main"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void routesQualityGateBranchWithSlashAsQueryParameter() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID projectRepositoryId = UUID.randomUUID();
        QualityGateDto response = new QualityGateDto();
        response.setRequiredChecks(List.of("DRY_RUN"));
        when(service.getQualityGate(actorId, projectId, projectRepositoryId, "feat/testset-e2e")).thenReturn(response);

        mockMvc.perform(get("/api/v1/projects/{projectId}/repositories/{projectRepositoryId}/quality-gates",
                        projectId, projectRepositoryId)
                        .param("branch", "feat/testset-e2e"))
                .andExpect(status().isOk());

        verify(service).getQualityGate(actorId, projectId, projectRepositoryId, "feat/testset-e2e");
    }

    @Test
    void updatesQualityGateBranchWithSlashAsQueryParameter() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID projectRepositoryId = UUID.randomUUID();
        QualityGateDto response = new QualityGateDto();
        response.setRequiredChecks(List.of("CQ_PLUS_ONE"));
        when(service.updateQualityGate(eq(actorId), eq(projectId), eq(projectRepositoryId), eq("feat/testset-e2e"),
                org.mockito.ArgumentMatchers.any())).thenReturn(response);

        mockMvc.perform(put("/api/v1/projects/{projectId}/repositories/{projectRepositoryId}/quality-gates",
                        projectId, projectRepositoryId)
                        .param("branch", "feat/testset-e2e")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requiredChecks\":[\"CQ_PLUS_ONE\"],\"requiredTestsetIds\":[]}"))
                .andExpect(status().isOk());

        verify(service).updateQualityGate(eq(actorId), eq(projectId), eq(projectRepositoryId), eq("feat/testset-e2e"),
                org.mockito.ArgumentMatchers.any());
    }
}
