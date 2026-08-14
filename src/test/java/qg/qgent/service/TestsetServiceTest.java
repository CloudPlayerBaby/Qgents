package qg.qgent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.api.ApiException;
import qg.qgent.dto.TestsetCreateRequest;
import qg.qgent.dto.TestsetPassRule;
import qg.qgent.dto.TestsetResponse;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TestsetEntity;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.RepositoryBranchConfigTestsetMapper;
import qg.qgent.mapper.TestsetMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestsetServiceTest {
    private final TestsetMapper testsets = mock(TestsetMapper.class);
    private final ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
    private final RepositoryBranchConfigTestsetMapper references = mock(RepositoryBranchConfigTestsetMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final TestsetService service = new TestsetService(testsets, repositories, references, access);
    private final UUID projectId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();

    @BeforeEach
    void repositoryBelongsToProject() {
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        when(repositories.selectById(repositoryId)).thenReturn(repository);
    }

    @Test
    void createStoresPublicFieldsInDefinitionJson() {
        TestsetResponse response = service.create(projectId, actor, request());

        ArgumentCaptor<TestsetEntity> captor = ArgumentCaptor.forClass(TestsetEntity.class);
        verify(access).requireProjectAdmin(projectId, actor);
        verify(testsets).insert(captor.capture());
        TestsetEntity stored = captor.getValue();
        assertEquals(repositoryId, stored.getProjectRepositoryId());
        assertEquals("./mvnw test", stored.getDefinition().get("command"));
        assertEquals("ENABLED", response.getStatus());
        assertEquals(900, response.getTimeoutSeconds());
    }

    @Test
    void repositoryFromAnotherProjectIsRejected() {
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(UUID.randomUUID());
        when(repositories.selectById(repositoryId)).thenReturn(repository);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(projectId, actor, request()));

        assertEquals("REPOSITORY_NOT_FOUND", error.code());
        verify(testsets, never()).insert(any(TestsetEntity.class));
    }

    @Test
    void deleteIsBlockedWhileBranchGateReferencesTestset() {
        UUID testsetId = UUID.randomUUID();
        TestsetEntity value = new TestsetEntity();
        value.setId(testsetId);
        value.setProjectId(projectId);
        when(testsets.selectById(testsetId)).thenReturn(value);
        when(references.countByTestsetId(testsetId)).thenReturn(1L);

        ApiException error = assertThrows(ApiException.class,
                () -> service.delete(projectId, testsetId, actor));

        assertEquals("TESTSET_IN_USE", error.code());
        verify(testsets, never()).deleteById(testsetId);
    }

    @Test
    void disableIsBlockedWhileBranchGateReferencesTestset() {
        UUID testsetId = UUID.randomUUID();
        TestsetEntity value = new TestsetEntity(); value.setId(testsetId); value.setProjectId(projectId);
        value.setStatus("ENABLED");
        when(testsets.selectById(testsetId)).thenReturn(value);
        when(references.countByTestsetId(testsetId)).thenReturn(1L);

        ApiException error = assertThrows(ApiException.class,
                () -> service.setEnabled(projectId, testsetId, actor, false));

        assertEquals("TESTSET_IN_USE", error.code());
        verify(testsets, never()).updateById(any(TestsetEntity.class));
    }

    @Test
    void blankCommandAndDuplicateTagsAreRejectedByService() {
        TestsetCreateRequest blank = request(); blank.setCommand("   ");
        assertEquals("INVALID_TESTSET_COMMAND",
                assertThrows(ApiException.class, () -> service.create(projectId, actor, blank)).code());
        TestsetCreateRequest duplicate = request(); duplicate.setScopeTags(List.of("unit", " unit "));
        assertEquals("INVALID_TESTSET_TAGS",
                assertThrows(ApiException.class, () -> service.create(projectId, actor, duplicate)).code());
    }

    private TestsetCreateRequest request() {
        TestsetPassRule rule = new TestsetPassRule();
        rule.setType("EXIT_CODE");
        rule.setExpected(0);
        TestsetCreateRequest request = new TestsetCreateRequest();
        request.setName("后端单元测试");
        request.setRepositoryId(repositoryId);
        request.setScopeTags(List.of("backend", "unit"));
        request.setCommand("./mvnw test");
        request.setTimeoutSeconds(900);
        request.setPassRule(rule);
        request.setAcceptanceNotes("覆盖登录场景");
        return request;
    }
}
