package qg.qgent.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GitHub App 宸叉巿鏉冧粨搴撶殑闀滃儚鍏冩暟鎹€?
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubRepositoryResponse {
    /** Qgents 浠撳簱闀滃儚 ID銆?*/
    private UUID id;

    /** GitHub 鎻愪緵鐨勪粨搴撴暟瀛?ID銆?*/
    private long providerRepositoryId;

    /** GitHub 浠撳簱鎵€鏈夎€呯櫥褰曞悕銆?*/
    private String ownerLogin;

    /** GitHub 浠撳簱鍚嶇О銆?*/
    private String name;

    /** GitHub 浠撳簱榛樿鍒嗘敮銆?*/
    private String defaultBranch;

    /** GitHub 浠撳簱鍙鎬с€?*/
    private String visibility;

    /** GitHub 鏄惁宸插綊妗ｃ€?*/
    private boolean archived;

    /** 浠撳簱鍏冩暟鎹渶杩戝悓姝ユ椂闂达紝UTC銆?*/
    private LocalDateTime syncedAt;
}
