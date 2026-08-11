package qg.qgent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 回答 WAITING_INPUT 输入请求。例如 {"answer":{"value":"main"}}。
 */
@Data
public class InputReplyRequest {
    /** 用户回答 JSON。 */
    @NotNull
    private Map<String, Object> answer;
}
