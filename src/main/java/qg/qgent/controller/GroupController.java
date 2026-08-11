package qg.qgent.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.GroupCreateRequest;
import qg.qgent.dto.GroupUpdateRequest;
import qg.qgent.service.GroupService;
import qg.qgent.service.IdempotencyService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 需求群接口（契约 §7 统一 Group）。
 */
@RestController
@RequestMapping("/api/v1")
public class GroupController {

    private final GroupService groupService;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public GroupController(GroupService groupService, IdempotencyService idempotency, ObjectMapper mapper) {
        this.groupService = groupService;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    /**
     * 项目总群与需求群列表，按最近活跃排序。
     */
    @GetMapping("/projects/{projectId}/groups")
    public ApiResponse<?> list(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            HttpServletRequest request) {
        return ok(groupService.list(userId, projectId), request);
    }

    /**
     * 创建 REQUIREMENT 需求群（写操作，需 Idempotency-Key）。
     */
    @PostMapping("/projects/{projectId}/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody GroupCreateRequest body, HttpServletRequest request) {
        return write("POST:/api/v1/projects/{projectId}/groups", idempotencyKey, userId, body,
                HttpStatus.CREATED.value(), () -> ok(groupService.create(userId, projectId, body), request));
    }

    /**
     * 获取需求群详情。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}")
    public ApiResponse<?> get(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId, HttpServletRequest request) {
        return ok(groupService.get(userId, projectId, groupId), request);
    }

    /**
     * 修改需求群标题、描述和关联仓库（写操作，需 Idempotency-Key）。
     */
    @PatchMapping("/projects/{projectId}/groups/{groupId}")
    public ApiResponse<?> update(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody GroupUpdateRequest body, HttpServletRequest request) {
        return write("PATCH:/api/v1/projects/{projectId}/groups/{groupId}", idempotencyKey, userId, body,
                HttpStatus.OK.value(), () -> ok(groupService.update(userId, projectId, groupId, body), request));
    }

    /**
     * 归档需求群（写操作，需 Idempotency-Key）。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/archive")
    public ApiResponse<?> archive(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return write("POST:/api/v1/projects/{projectId}/groups/{groupId}/archive", idempotencyKey, userId,
                List.of(projectId, groupId), HttpStatus.OK.value(),
                () -> ok(groupService.archive(userId, projectId, groupId), request));
    }

    /**
     * 获取群成员列表（群成员即项目成员，无角色）。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/members")
    public ApiResponse<?> members(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId, HttpServletRequest request) {
        return ok(groupService.members(userId, projectId, groupId), request);
    }

    /**
     * 当前用户退出群聊（即移出本项目成员，写操作，需 Idempotency-Key）。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/leave")
    public ApiResponse<?> leave(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        return write("POST:/api/v1/projects/{projectId}/groups/{groupId}/leave", idempotencyKey, userId,
                List.of(projectId, groupId), HttpStatus.OK.value(),
                () -> {
                    groupService.leave(userId, projectId, groupId);
                    return ok(Map.of(), request);
                });
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /** 用 Idempotency-Key 包裹写操作，返回本次或回放结果。 */
    private ApiResponse<?> write(String scope, String key, UUID userId, Object requestBody, int successStatus,
            Supplier<ApiResponse<?>> action) {
        JsonNode result = idempotency.run(scope, key, userId, requestBody, successStatus,
                () -> mapper.valueToTree(action.get()));
        return fromJson(result);
    }

    private ApiResponse<?> fromJson(JsonNode node) {
        try {
            return mapper.treeToValue(node, ApiResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应解析失败", e);
        }
    }
}
