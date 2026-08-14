package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.TestsetCreateRequest;
import qg.qgent.dto.TestsetResponse;
import qg.qgent.dto.TestsetUpdateRequest;
import qg.qgent.security.CurrentActorProvider;
import qg.qgent.service.TestsetService;

import java.util.List;
import java.util.UUID;

/** v1.4.0 Testset 配置接口。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/testsets")
public class TestsetController {
    private final TestsetService service;
    private final CurrentActorProvider actor;

    public TestsetController(TestsetService service, CurrentActorProvider actor) {
        this.service = service;
        this.actor = actor;
    }

    @GetMapping
    public ApiResponse<List<TestsetResponse>> list(@PathVariable UUID projectId,
            @RequestParam(required = false) UUID repositoryId, @RequestParam(required = false) String status,
            HttpServletRequest request) {
        return ok(service.list(projectId, actor.currentUserId(), repositoryId, status), request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TestsetResponse> create(@PathVariable UUID projectId,
            @Valid @RequestBody TestsetCreateRequest body, HttpServletRequest request) {
        return ok(service.create(projectId, actor.currentUserId(), body), request);
    }

    @GetMapping("/{testsetId}")
    public ApiResponse<TestsetResponse> get(@PathVariable UUID projectId, @PathVariable UUID testsetId,
            HttpServletRequest request) {
        return ok(service.get(projectId, testsetId, actor.currentUserId()), request);
    }

    @PatchMapping("/{testsetId}")
    public ApiResponse<TestsetResponse> update(@PathVariable UUID projectId, @PathVariable UUID testsetId,
            @Valid @RequestBody TestsetUpdateRequest body, HttpServletRequest request) {
        return ok(service.update(projectId, testsetId, actor.currentUserId(), body), request);
    }

    @PostMapping("/{testsetId}/enable")
    public ApiResponse<TestsetResponse> enable(@PathVariable UUID projectId, @PathVariable UUID testsetId,
            HttpServletRequest request) {
        return ok(service.setEnabled(projectId, testsetId, actor.currentUserId(), true), request);
    }

    @PostMapping("/{testsetId}/disable")
    public ApiResponse<TestsetResponse> disable(@PathVariable UUID projectId, @PathVariable UUID testsetId,
            HttpServletRequest request) {
        return ok(service.setEnabled(projectId, testsetId, actor.currentUserId(), false), request);
    }

    @DeleteMapping("/{testsetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId, @PathVariable UUID testsetId) {
        service.delete(projectId, testsetId, actor.currentUserId());
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
