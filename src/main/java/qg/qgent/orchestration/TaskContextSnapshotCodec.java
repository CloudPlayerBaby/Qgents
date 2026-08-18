package qg.qgent.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import qg.qgent.dto.GroupContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task 默认上下文快照的内部编解码器。
 * <p>
 * 快照保存在 Task 自身而非执行产物中：PLAN 没有 TaskRun，且群消息和 Memory 仅是 Agent 输入，
 * 不能伪装成用户可见的 Step/Run 产物。版本字段使后续结构演进可显式拒绝而非静默混用实时数据。
 */
@Component
public class TaskContextSnapshotCodec {

    private static final int VERSION = 1;

    private final ObjectMapper objectMapper;

    public TaskContextSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将已校验的群上下文封装为不可变 Task 快照值。
     */
    public Map<String, Object> encode(GroupContext context) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", VERSION);
        value.put("groupContext", objectMapper.convertValue(context, new TypeReference<LinkedHashMap<String, Object>>() {
        }));
        return value;
    }

    /**
     * 读取已持久化快照；结构不兼容或损坏时返回 null，调用方不得回退覆盖该快照。
     */
    public GroupContext decode(Map<String, Object> snapshot) {
        if (snapshot == null || !(snapshot.get("version") instanceof Number version)
                || version.intValue() != VERSION || !(snapshot.get("groupContext") instanceof Map<?, ?> context)) {
            return null;
        }
        return objectMapper.convertValue(context, GroupContext.class);
    }
}
