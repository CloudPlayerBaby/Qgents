package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.*;
import qg.qgent.service.DiffService;

import java.util.UUID;

/**
 * Task Diff review endpoints; accepting records approval but controlled Git
 * commit remains an executor action.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/diffs")
public class DiffController {
        private final DiffService service;

        public DiffController(DiffService service) {
                this.service = service;
        }

        @GetMapping
        public ApiPageResponse<DiffListItemResponse> list(@PathVariable UUID projectId,
                        @RequestParam(required = false) UUID taskId, @AuthenticationPrincipal UUID actor,
                        @RequestParam(required = false) String cursor,
                        @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
                return service.list(projectId, taskId, actor, cursor, limit, id(request));
        }

        @GetMapping("/{diffId}")
        public ApiResponse<?> get(@PathVariable UUID projectId, @PathVariable UUID diffId,
                        @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
                return ok(service.get(projectId, diffId, actor), request);
        }

        @GetMapping("/{diffId}/files")
        public ApiPageResponse<DiffFileResponse> files(@PathVariable UUID projectId,
                        @PathVariable UUID diffId, @AuthenticationPrincipal UUID actor,
                        @RequestParam(required = false) String cursor,
                        @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
                return service.files(projectId, diffId, actor, cursor, limit, id(request));
        }

        @GetMapping("/{diffId}/comments")
        public ApiResponse<?> comments(@PathVariable UUID projectId,
                        @PathVariable UUID diffId, @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
                return ok(service.comments(projectId, diffId, actor), request);
        }

        @PostMapping("/{diffId}/comments")
        public ApiResponse<?> comment(@PathVariable UUID projectId,
                        @PathVariable UUID diffId, @AuthenticationPrincipal UUID actor,
                        @Valid @RequestBody DiffCommentRequest body,
                        HttpServletRequest request) {
                return ok(service.addComment(projectId, diffId, actor, body), request);
        }

        @PostMapping("/{diffId}/accept")
        public ApiResponse<?> accept(@PathVariable UUID projectId,
                        @PathVariable UUID diffId, @AuthenticationPrincipal UUID actor,
                        @RequestBody(required = false) DiffDecisionRequest body,
                        HttpServletRequest request) {
                return ok(service.decide(projectId, diffId, actor, true, body == null ? null : body.getReason()),
                                request);
        }

        @PostMapping("/{diffId}/reject")
        public ApiResponse<?> reject(@PathVariable UUID projectId,
                        @PathVariable UUID diffId, @AuthenticationPrincipal UUID actor,
                        @Valid @RequestBody DiffDecisionRequest body,
                        HttpServletRequest request) {
                return ok(service.decide(projectId, diffId, actor, false, body.getReason()), request);
        }

        private ApiResponse<?> ok(Object value, HttpServletRequest request) {
                return ApiResponse.ok(value, id(request));
        }

        private String id(HttpServletRequest request) {
                return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        }
}
