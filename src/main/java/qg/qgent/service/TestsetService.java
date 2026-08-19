package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.TestsetCreateRequest;
import qg.qgent.dto.TestsetPassRule;
import qg.qgent.dto.TestsetResponse;
import qg.qgent.dto.TestsetUpdateRequest;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TestsetEntity;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.RepositoryBranchConfigTestsetMapper;
import qg.qgent.mapper.TestsetMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理项目级可复用 Testset，并在服务端校验项目与仓库归属。
 */
@Service
public class TestsetService {
    private final TestsetMapper testsets;
    private final ProjectRepositoryMapper repositories;
    private final RepositoryBranchConfigTestsetMapper gateReferences;
    private final ProjectAccessService access;

    public TestsetService(TestsetMapper testsets, ProjectRepositoryMapper repositories,
                          RepositoryBranchConfigTestsetMapper gateReferences, ProjectAccessService access) {
        this.testsets = testsets;
        this.repositories = repositories;
        this.gateReferences = gateReferences;
        this.access = access;
    }

    public List<TestsetResponse> list(UUID projectId, UUID actor, UUID repositoryId, String status) {
        access.requireProjectMember(projectId, actor);
        if (repositoryId != null) requireRepository(projectId, repositoryId);
        if (status != null && !status.isBlank() && !List.of("ENABLED", "DISABLED").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TESTSET_STATUS", "Testset 状态只能是 ENABLED 或 DISABLED");
        }
        return testsets.selectList(Wrappers.<TestsetEntity>lambdaQuery()
                        .eq(TestsetEntity::getProjectId, projectId)
                        .eq(repositoryId != null, TestsetEntity::getProjectRepositoryId, repositoryId)
                        .eq(status != null && !status.isBlank(), TestsetEntity::getStatus, status)
                        .orderByAsc(TestsetEntity::getCreatedAt))
                .stream().map(this::response).toList();
    }

    public TestsetResponse get(UUID projectId, UUID testsetId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        return response(require(projectId, testsetId));
    }

    @Transactional
    public TestsetResponse create(UUID projectId, UUID actor, TestsetCreateRequest request) {
        access.requireProjectAdmin(projectId, actor);
        requireRepository(projectId, request.getRepositoryId());
        ValidatedDefinition validated = validateDefinition(request.getName(), request.getScopeTags(),
                request.getCommand(), request.getTimeoutSeconds(), request.getPassRule());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TestsetEntity value = new TestsetEntity();
        value.setId(UuidV7.next());
        value.setProjectId(projectId);
        value.setProjectRepositoryId(request.getRepositoryId());
        value.setName(validated.name());
        value.setDefinition(definition(validated.tags(), validated.command(), validated.timeout(),
                validated.rule(), request.getAcceptanceNotes()));
        value.setStatus("ENABLED");
        value.setCreatedBy(actor);
        value.setCreatedAt(now);
        value.setUpdatedAt(now);
        testsets.insert(value);
        return response(value);
    }

    @Transactional
    public TestsetResponse update(UUID projectId, UUID testsetId, UUID actor, TestsetUpdateRequest request) {
        access.requireProjectAdmin(projectId, actor);
        TestsetEntity value = require(projectId, testsetId);
        UUID repositoryId = request.getRepositoryId() == null ? value.getProjectRepositoryId() : request.getRepositoryId();
        requireRepository(projectId, repositoryId);
        if (!repositoryId.equals(value.getProjectRepositoryId()) && gateReferences.countByTestsetId(testsetId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "TESTSET_IN_USE",
                    "Testset 仍被质量门禁引用，不能更换所属仓库");
        }
        Map<String, Object> old = value.getDefinition() == null ? Map.of() : value.getDefinition();
        List<String> tags = request.getScopeTags() == null ? strings(old.get("scopeTags")) : request.getScopeTags();
        String command = request.getCommand() == null ? string(old.get("command")) : request.getCommand();
        Integer timeout = request.getTimeoutSeconds() == null ? integer(old.get("timeoutSeconds")) : request.getTimeoutSeconds();
        TestsetPassRule passRule = request.getPassRule() == null ? passRule(old.get("passRule")) : request.getPassRule();
        String notes = request.getAcceptanceNotes() == null ? string(old.get("acceptanceNotes")) : request.getAcceptanceNotes();
        ValidatedDefinition validated = validateDefinition(
                request.getName() == null ? value.getName() : request.getName(), tags, command, timeout, passRule);
        value.setProjectRepositoryId(repositoryId);
        value.setName(validated.name());
        value.setDefinition(definition(validated.tags(), validated.command(), validated.timeout(), validated.rule(), notes));
        value.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        testsets.updateById(value);
        return response(value);
    }

    @Transactional
    public TestsetResponse setEnabled(UUID projectId, UUID testsetId, UUID actor, boolean enabled) {
        access.requireProjectAdmin(projectId, actor);
        TestsetEntity value = require(projectId, testsetId);
        if (!enabled && gateReferences.countByTestsetId(testsetId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "TESTSET_IN_USE",
                    "Testset 仍被质量门禁引用，不能停用");
        }
        value.setStatus(enabled ? "ENABLED" : "DISABLED");
        value.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        testsets.updateById(value);
        return response(value);
    }

    @Transactional
    public void delete(UUID projectId, UUID testsetId, UUID actor) {
        access.requireProjectAdmin(projectId, actor);
        require(projectId, testsetId);
        if (gateReferences.countByTestsetId(testsetId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "TESTSET_IN_USE", "Testset 仍被质量门禁引用，不能删除");
        }
        testsets.deleteById(testsetId);
    }

    private TestsetEntity require(UUID projectId, UUID id) {
        TestsetEntity value = testsets.selectById(id);
        if (value == null || !projectId.equals(value.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TESTSET_NOT_FOUND", "Testset 不存在或不可见");
        }
        return value;
    }

    private void requireRepository(UUID projectId, UUID id) {
        ProjectRepositoryEntity value = repositories.selectById(id);
        if (value == null || !projectId.equals(value.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "仓库不存在或不可见");
        }
    }

    private Map<String, Object> definition(List<String> tags, String command, Integer timeout,
                                           TestsetPassRule rule, String notes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scopeTags", tags == null ? List.of() : List.copyOf(tags));
        result.put("command", command);
        result.put("timeoutSeconds", timeout);
        result.put("passRule", Map.of("type", rule.getType(), "expected", rule.getExpected()));
        result.put("acceptanceNotes", notes);
        return result;
    }

    private ValidatedDefinition validateDefinition(String name, List<String> tags, String command, Integer timeout,
                                                   TestsetPassRule rule) {
        String normalizedName = name == null ? "" : name.trim();
        String normalizedCommand = normalizeCommand(command);
        if (normalizedName.isEmpty() || normalizedName.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TESTSET_NAME", "Testset 名称不能为空且最多 255 字符");
        }
        if (normalizedCommand.isEmpty() || normalizedCommand.length() > 4096) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TESTSET_COMMAND", "Testset 命令不能为空且最多 4096 字符");
        }
        if (timeout == null || timeout < 1 || timeout > 3600) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TESTSET_TIMEOUT", "Testset 超时必须为 1 到 3600 秒");
        }
        if (rule == null || !"EXIT_CODE".equals(rule.getType()) || rule.getExpected() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TESTSET_PASS_RULE", "当前仅支持 EXIT_CODE 通过规则");
        }
        if (tags == null || tags.size() > 32) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TESTSET_TAGS", "scopeTags 最多 32 个");
        }
        List<String> normalizedTags = tags.stream().map(tag -> tag == null ? "" : tag.trim()).toList();
        if (normalizedTags.stream().anyMatch(tag -> tag.isEmpty() || tag.length() > 64)
                || normalizedTags.stream().distinct().count() != normalizedTags.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TESTSET_TAGS",
                    "scopeTags 不能为空、重复或超过 64 字符");
        }
        return new ValidatedDefinition(normalizedName, List.copyOf(normalizedTags), normalizedCommand, timeout, rule);
    }

    /** 将历史裸 Wrapper 规范化为工作区相对命令；执行端仍会再次兼容旧数据。 */
    private String normalizeCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        return switch (normalized) {
            case "gradlew test" -> "./gradlew test";
            case "mvnw test" -> "./mvnw test";
            default -> normalized;
        };
    }

    private TestsetResponse response(TestsetEntity value) {
        Map<String, Object> d = value.getDefinition() == null ? Map.of() : value.getDefinition();
        return new TestsetResponse(value.getId(), value.getProjectId(), value.getProjectRepositoryId(), value.getName(),
                strings(d.get("scopeTags")), string(d.get("command")), integer(d.get("timeoutSeconds")),
                passRule(d.get("passRule")), string(d.get("acceptanceNotes")), value.getStatus(), value.getCreatedBy(),
                iso(value.getCreatedAt()), iso(value.getUpdatedAt()));
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(Object value) {
        return value instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
    }

    private TestsetPassRule passRule(Object value) {
        Map<String, Object> map = value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
        TestsetPassRule rule = new TestsetPassRule();
        rule.setType(string(map.getOrDefault("type", "EXIT_CODE")));
        rule.setExpected(integer(map.getOrDefault("expected", 0)));
        return rule;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integer(Object value) {
        if (value == null) return null;
        try {
            return value instanceof Number number ? number.intValue() : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    private record ValidatedDefinition(String name, List<String> tags, String command, Integer timeout,
                                       TestsetPassRule rule) {
    }
}
