package qg.qgent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectControllerRouteTest {
    @Test
    void exposesTenContractRoutesAndSevenIdempotentWrites() {
        Set<String> routes = new HashSet<>();
        int idempotentWrites = 0;
        for (Method method : ProjectController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GetMapping.class)) {
                routes.add("GET " + method.getAnnotation(GetMapping.class).value()[0]);
            } else if (method.isAnnotationPresent(PostMapping.class)) {
                routes.add("POST " + method.getAnnotation(PostMapping.class).value()[0]);
            } else if (method.isAnnotationPresent(PatchMapping.class)) {
                routes.add("PATCH " + method.getAnnotation(PatchMapping.class).value()[0]);
            } else if (method.isAnnotationPresent(DeleteMapping.class)) {
                routes.add("DELETE " + method.getAnnotation(DeleteMapping.class).value()[0]);
            } else {
                continue;
            }
            boolean hasKey = java.util.Arrays.stream(method.getParameters())
                    .anyMatch(parameter -> parameter.isAnnotationPresent(RequestHeader.class)
                            && "Idempotency-Key".equals(parameter.getAnnotation(RequestHeader.class).value()));
            if (hasKey) {
                idempotentWrites++;
            }
        }

        assertEquals(Set.of(
                "POST /teams/{teamId}/projects", "GET /teams/{teamId}/projects",
                "GET /projects/{projectId}", "PATCH /projects/{projectId}",
                "POST /projects/{projectId}/archive", "POST /projects/{projectId}/restore",
                "GET /projects/{projectId}/members", "POST /projects/{projectId}/members",
                "PATCH /projects/{projectId}/members/{userId}",
                "DELETE /projects/{projectId}/members/{userId}"), routes);
        assertEquals(7, idempotentWrites);
    }
}
