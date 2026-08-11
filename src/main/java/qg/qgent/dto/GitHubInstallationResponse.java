package qg.qgent.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 鍥㈤槦宸叉巿鏉冪殑 GitHub App 瀹夎璁板綍銆?
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubInstallationResponse {
    /** Qgents 瀹夎璁板綍 ID銆?*/
    private UUID id;

    /** GitHub 鎻愪緵鐨勫畨瑁呮暟瀛?ID銆?*/
    private long providerInstallationId;

    /** GitHub 鎺堟潈璐﹀彿鐧诲綍鍚嶃€?*/
    private String accountLogin;

    /** GitHub 鎺堟潈璐﹀彿绫诲瀷銆?*/
    private String accountType;

    /** 瀹夎鐘舵€併€?*/
    private String status;

    /** 瀹夎璁板綍鏈€杩戝悓姝ユ椂闂达紝UTC銆?*/
    private LocalDateTime updatedAt;
}
