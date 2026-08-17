package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.QualityCheckResultEntity;
import qg.qgent.entity.TaskExecutionArtifactEntity;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.QualityCheckResultMapper;
import qg.qgent.mapper.TaskExecutionArtifactMapper;
import qg.qgent.mapper.TaskMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MrQualityGateServiceTest {
    @Test
    void writesAiReviewButDoesNotPretendGithubMergeabilityIsDryRun() {
        MergeRequestMapper mergeRequests = mock(MergeRequestMapper.class);
        TaskExecutionArtifactMapper artifacts = mock(TaskExecutionArtifactMapper.class);
        QualityCheckResultMapper checks = mock(QualityCheckResultMapper.class);
        MergeRequestEntity mr = mergeRequest();
        TaskExecutionArtifactEntity artifact = new TaskExecutionArtifactEntity();
        artifact.setSummary(Map.of("review", Map.of("success", true, "summary", "review passed")));
        when(artifacts.selectOne(any())).thenReturn(artifact);
        when(mergeRequests.selectByIdForUpdate(mr.getId())).thenReturn(mr);
        when(checks.selectOne(any())).thenReturn(null);
        MrQualityGateService service = new MrQualityGateService(mergeRequests, artifacts, mock(TaskMapper.class),
                null, checks, null);

        service.onPullRequestCreated(mr);

        ArgumentCaptor<QualityCheckResultEntity> captured = ArgumentCaptor.forClass(QualityCheckResultEntity.class);
        verify(checks, times(1)).insert(captured.capture());
        assertEquals("AI_REVIEW", captured.getValue().getCheckType());
        assertEquals("PASSED", captured.getValue().getStatus());
    }

    @Test
    void missingReviewArtifactDoesNotFabricateQualityResult() {
        TaskExecutionArtifactMapper artifacts = mock(TaskExecutionArtifactMapper.class);
        when(artifacts.selectOne(any())).thenReturn(null);
        QualityCheckResultMapper checks = mock(QualityCheckResultMapper.class);
        MrQualityGateService service = new MrQualityGateService(mock(MergeRequestMapper.class), artifacts,
                mock(TaskMapper.class), null, checks, null);

        service.onPullRequestCreated(mergeRequest());

        verify(checks, never()).insert(any(QualityCheckResultEntity.class));
    }

    private MergeRequestEntity mergeRequest() {
        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(UUID.randomUUID());
        mr.setTaskId(UUID.randomUUID());
        mr.setHeadCommit("a".repeat(40));
        return mr;
    }
}
