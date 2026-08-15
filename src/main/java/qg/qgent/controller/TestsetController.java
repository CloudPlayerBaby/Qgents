package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.TestsetCreateRequest;
import qg.qgent.dto.TestsetResponse;
import qg.qgent.dto.TestsetUpdateRequest;
import qg.qgent.security.CurrentActorProvider;
import qg.qgent.service.TestsetService;

import java.util.List;
import java.util.UUID;

/**
 * Testset 配置接口
 * Testset 的查询、创建、更新、启停与删除配置管理。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/testsets")
public class TestsetController {
    private final TestsetService service;
    private final CurrentActorProvider actor;

    public TestsetController(TestsetService service, CurrentActorProvider actor) {
        this.service = service;
        this.actor = actor;
    }

    /**
     * 契约 §10：按项目、仓库与状态筛选查询 Testset 列表。
     */
    @GetMapping
    public ApiResponse<List<TestsetResponse>> list(@PathVariable UUID projectId,
                                                   @RequestParam(required = false) UUID repositoryId, @RequestParam(required = false) String status,
                                                   HttpServletRequest request) {
        return ok(service.list(projectId, actor.currentUserId(), repositoryId, status), request);
    }

    /**
     * 契约 §10：创建 Testset 配置。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TestsetResponse> create(@PathVariable UUID projectId,
                                               @Valid @RequestBody TestsetCreateRequest body, HttpServletRequest request) {
        return ok(service.create(projectId, actor.currentUserId(), body), request);
    }

    /**
     * 契约 §10：获取单个 Testset 配置详情。
     */
    @GetMapping("/{testsetId}")
    public ApiResponse<TestsetResponse> get(@PathVariable UUID projectId, @PathVariable UUID testsetId,
                                            HttpServletRequest request) {
        return ok(service.get(projectId, testsetId, actor.currentUserId()), request);
    }

    /**
     * 契约 §10：更新 Testset 配置。
     */
    @PatchMapping("/{testsetId}")
    public ApiResponse<TestsetResponse> update(@PathVariable UUID projectId, @PathVariable UUID testsetId,
                                               @Valid @RequestBody TestsetUpdateRequest body, HttpServletRequest request) {
        return ok(service.update(projectId, testsetId, actor.currentUserId(), body), request);
    }

    /**
     * 契约 §10：启用 Testset。
     */
    @PostMapping("/{testsetId}/enable")
    public ApiResponse<TestsetResponse> enable(@PathVariable UUID projectId, @PathVariable UUID testsetId,
                                               HttpServletRequest request) {
        return ok(service.setEnabled(projectId, testsetId, actor.currentUserId(), true), request);
    }

    /**
     * 契约 §10：停用 Testset。
     */
    @PostMapping("/{testsetId}/disable")
    public ApiResponse<TestsetResponse> disable(@PathVariable UUID projectId, @PathVariable UUID testsetId,
                                                HttpServletRequest request) {
        return ok(service.setEnabled(projectId, testsetId, actor.currentUserId(), false), request);
    }

    /**
     * 契约 §10：删除 Testset。
     */
    @DeleteMapping("/{testsetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId, @PathVariable UUID testsetId) {
        service.delete(projectId, testsetId, actor.currentUserId());
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
