package qg.qgent.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import qg.qgent.dto.MergeRequestCommitListResponse;
import qg.qgent.dto.MergeRequestCommitResponse;
import qg.qgent.service.MergeRequestCommentService;
import qg.qgent.service.MergeRequestService;
import qg.qgent.service.MrPreflightService;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MR 控制器提交记录路由契约测试。
 */
class MergeRequestControllerTest {
    private final MergeRequestService mergeRequestService = mock(MergeRequestService.class);
    private final MergeRequestCommentService commentService = mock(MergeRequestCommentService.class);
    private final MrPreflightService preflightService = mock(MrPreflightService.class);
    private final UUID userId = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MergeRequestController(
                        mergeRequestService, commentService, preflightService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalResolver(userId))
                .build();
    }

    @Test
    void commitsReturnsGitHubCommitListAndForwardsLimit() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();
        MergeRequestCommitListResponse response = new MergeRequestCommitListResponse(4, List.of(
                new MergeRequestCommitResponse("sha123", "实现提交记录", "Alice", null,
                        "2026-08-22T00:00:00Z")));
        when(mergeRequestService.commits(any(), any(), any(), anyInt())).thenReturn(response);

        mockMvc.perform(get("/api/v1/projects/{projectId}/merge-requests/{mergeRequestId}/commits", projectId,
                        mergeRequestId).param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(4))
                .andExpect(jsonPath("$.data.items[0].sha").value("sha123"))
                .andExpect(jsonPath("$.data.items[0].authorUserId").doesNotExist());

        verify(mergeRequestService).commits(projectId, mergeRequestId, userId, 3);
    }

    /** standalone MockMvc 下解析 @AuthenticationPrincipal 为固定 userId。 */
    private static final class AuthenticationPrincipalResolver implements HandlerMethodArgumentResolver {
        private final Object principal;

        private AuthenticationPrincipalResolver(Object principal) {
            this.principal = principal;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            return principal;
        }
    }
}
