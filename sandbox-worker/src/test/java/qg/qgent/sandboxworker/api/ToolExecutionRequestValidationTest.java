package qg.qgent.sandboxworker.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionRequestValidationTest {
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validator = null;
    }

    @Test
    void acceptsRegisteredToolWithUnderscoreInOperationName() {
        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setExecutionId(UUID.randomUUID());
        request.setTool("file.ensure_trailing_newline");

        assertTrue(validator.validate(request).isEmpty());
    }
}
