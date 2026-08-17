package qg.qgent.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.DeliveryItem;
import qg.qgent.dto.DeliverySummaryResponse;
import qg.qgent.service.DeliveryCenterService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 交付中心聚合接口（契约 v1.8.0 §20，成员 B B01/B02）。
 * <p>
 * 只读聚合 CODE/MEMORY/SKILL 三类项目资源；写操作继续复用对应正式资源接口。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "交付中心", description = "DeliveryCenter 聚合列表与统计（只读）")
public class DeliveryCenterController {

    private final DeliveryCenterService deliveryCenter;

    public DeliveryCenterController(DeliveryCenterService deliveryCenter) {
        this.deliveryCenter = deliveryCenter;
    }

    /**
     * 契约 v1.8.0 §20：交付中心聚合列表（统一 cursor envelope）。
     */
    @GetMapping("/projects/{projectId}/delivery-items")
    public PagedApiResponse<DeliveryItem> list(@PathVariable UUID projectId,
                                               @AuthenticationPrincipal UUID userId,
                                               @RequestParam(required = false) String groupId,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String repositoryId,
                                               @RequestParam(required = false) String createdBy,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit,
                                               HttpServletRequest request) {
        return deliveryCenter.list(projectId, userId, groupId, type, status, repositoryId, createdBy,
                cursor, limit, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 v1.8.0 §20：交付中心聚合统计（筛选参数与 delivery-items 一致，针对完整筛选数据集）。
     */
    @GetMapping("/projects/{projectId}/delivery-summary")
    public DeliverySummaryResponse summary(@PathVariable UUID projectId,
                                           @AuthenticationPrincipal UUID userId,
                                           @RequestParam(required = false) String groupId,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String repositoryId,
                                           @RequestParam(required = false) String createdBy,
                                           HttpServletRequest request) {
        return deliveryCenter.summary(projectId, userId, groupId, type, status, repositoryId, createdBy);
    }

    /**
     * 契约成员 B P2：交付中心导出。筛选参数与 delivery-items 一致，返回 UTF-8 CSV
     * （含 BOM），并设置 {@code Content-Disposition: attachment}。只导出列表摘要，
     * 不包含完整 Memory/Skill 内容、Prompt、Token、凭据或代码 Patch。
     */
    @GetMapping("/projects/{projectId}/delivery-items/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID projectId,
                                         @AuthenticationPrincipal UUID userId,
                                         @RequestParam(required = false) String groupId,
                                         @RequestParam(required = false) String type,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String repositoryId,
                                         @RequestParam(required = false) String createdBy) {
        String csv = deliveryCenter.exportCsv(projectId, userId, groupId, type, status, repositoryId, createdBy);
        String filename = "delivery-items-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.now()) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
