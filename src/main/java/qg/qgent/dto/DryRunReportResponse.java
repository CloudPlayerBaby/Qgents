package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 试运行报告响应，含冲突与测试摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DryRunReportResponse {
    private String id;
    private String status;
    private Map<String, Object> report;
    private String createdAt;
}
