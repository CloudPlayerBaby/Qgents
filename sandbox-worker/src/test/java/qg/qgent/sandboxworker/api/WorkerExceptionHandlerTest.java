package qg.qgent.sandboxworker.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 协议层错误契约测试：缺请求体、Content-Type 不支持、方法不支持与未知路径都必须返回
 * 统一 {code,message} 错误体，而不是 Spring 默认错误页（主后端依赖 code 做错误映射）。
 */
class WorkerExceptionHandlerTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController(), new WorkerFallbackController())
                .setControllerAdvice(new WorkerExceptionHandler())
                .build();
    }

    @Test
    void missingBodyReturnsUnifiedError() throws Exception {
        mockMvc.perform(post("/probe")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void unsupportedMediaTypeReturnsUnifiedError() throws Exception {
        mockMvc.perform(post("/probe").contentType(MediaType.TEXT_PLAIN).content("raw"))
                .andExpect(status().is(415))
                .andExpect(jsonPath("$.code").value("INVALID_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void validationErrorIncludesFieldAndReason() throws Exception {
        mockMvc.perform(post("/validated-probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("value:")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("must not be blank")));
    }

    @Test
    void methodNotAllowedReturnsUnifiedError() throws Exception {
        mockMvc.perform(get("/probe")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void unknownPathReturnsUnifiedError() throws Exception {
        mockMvc.perform(get("/internal/v1/no-such-endpoint")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists());
    }

    @RestController
    static class ProbeController {
        @PostMapping("/probe")
        Map<String, Object> probe(@RequestBody Map<String, Object> body) {
            return body;
        }

        @PostMapping("/validated-probe")
        Map<String, Object> validatedProbe(@Valid @RequestBody ValidatedBody body) {
            return Map.of("value", body.value());
        }
    }

    record ValidatedBody(@NotBlank String value) {
    }
}
