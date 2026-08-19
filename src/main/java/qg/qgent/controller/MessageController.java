package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.MessageResponse;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.dto.PageSlice;
import qg.qgent.dto.TaskTriggerRequest;
import qg.qgent.service.MessageService;
import qg.qgent.service.TaskTriggerService;

import java.util.UUID;

/**
 * 群消息接口
 * 群消息的发送、分页拉取与从消息触发 Task。
 */
@RestController
@RequestMapping("/api/v1")
public class MessageController {

    private final MessageService messageService;
    private final TaskTriggerService taskTriggerService;

    public MessageController(MessageService messageService, TaskTriggerService taskTriggerService) {
        this.messageService = messageService;
        this.taskTriggerService = taskTriggerService;
    }

    /**
     * 契约 §7：发送文本、代码块、图片、文件或引用消息。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> send(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                               @PathVariable UUID groupId, @Valid @RequestBody MessageSendRequest body, HttpServletRequest request) {
        return ok(messageService.send(userId, projectId, groupId, body), request);
    }

    /**
     * 契约 §7：游标分页拉取群消息（新消息在前）。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/messages")
    public PagedApiResponse<MessageResponse> list(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                                  @PathVariable UUID groupId,
                                                  @RequestParam(value = "cursor", required = false) String cursor,
                                                  @RequestParam(value = "limit", defaultValue = "30") int limit, HttpServletRequest request) {
        PageSlice<MessageResponse> slice = messageService.list(userId, projectId, groupId, cursor, limit);
        return new PagedApiResponse<>(slice.getData(), slice.getPage(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 断线恢复增量消息：仅返回 sequence 大于 afterSequence 的消息，按 sequence 升序。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/messages/incremental")
    public PagedApiResponse<MessageResponse> listIncremental(@AuthenticationPrincipal UUID userId,
                                                              @PathVariable UUID projectId,
                                                              @PathVariable UUID groupId,
                                                              @RequestParam long afterSequence,
                                                              @RequestParam(defaultValue = "100") int limit,
                                                              HttpServletRequest request) {
        PageSlice<MessageResponse> slice = messageService.listAfterSequence(userId, projectId, groupId,
                afterSequence, limit);
        return new PagedApiResponse<>(slice.getData(), slice.getPage(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约「群聊@提及-后端接口补充」§六：按消息 ID 拉取单条群消息（通知跳转精确定位，
     * 目标消息不在当前分页窗口时前端调用后合并进列表再滚动高亮）。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/messages/{messageId}")
    public ApiResponse<?> getMessage(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                     @PathVariable UUID groupId, @PathVariable UUID messageId,
                                     HttpServletRequest request) {
        return ok(messageService.getMessage(userId, projectId, groupId, messageId), request);
    }

    /**
     * 契约 §7：从群消息显式触发 Task。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/messages/{messageId}/trigger-task")
    public ApiResponse<?> triggerTask(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                      @PathVariable UUID groupId, @PathVariable UUID messageId,
                                      @Valid @RequestBody TaskTriggerRequest body, HttpServletRequest request) {
        return ok(taskTriggerService.trigger(userId, projectId, groupId, messageId, body), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
