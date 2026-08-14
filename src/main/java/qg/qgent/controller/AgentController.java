package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.AgentResponse;
import qg.qgent.security.CurrentActorProvider;
import qg.qgent.service.AgentService;

import java.util.List;
import java.util.UUID;

/** Team Agent resource endpoints. */
@RestController
@RequestMapping("/api/v1")
public class AgentController {
    private final AgentService service;
    private final CurrentActorProvider currentActor;

    public AgentController(AgentService service, CurrentActorProvider currentActor) {
        this.service = service;
        this.currentActor = currentActor;
    }

    @GetMapping("/teams/{teamId}/agents")
    public ApiResponse<List<AgentResponse>> list(@PathVariable UUID teamId, HttpServletRequest request) {
        return ApiResponse.ok(service.list(currentActor.currentUserId(), teamId),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
