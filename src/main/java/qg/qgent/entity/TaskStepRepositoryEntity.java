package qg.qgent.entity;
import lombok.Data;
import java.util.UUID;
/** Composite-key repository access scope granted to one workflow step. */
@Data public class TaskStepRepositoryEntity {
    /** Scoped task step identifier. */ private UUID taskStepId;
    /** Repository binding already attached to the same task. */ private UUID projectRepositoryId;
    /** Access mode: READ or WRITE. */ private String accessMode;
}
