package qg.qgent.entity;
import lombok.Data;
import java.util.UUID;
/** Composite-key directed dependency requiring one step to finish before another can start. */
@Data public class TaskStepDependencyEntity {
    /** Dependent step identifier. */ private UUID taskStepId;
    /** Required predecessor step identifier in the same task. */ private UUID dependsOnTaskStepId;
}
