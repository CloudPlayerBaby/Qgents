package qg.qgent.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 椤圭洰涓庡凡鎺堟潈 GitHub 浠撳簱涔嬮棿鐨勭粦瀹氳褰曘€?
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectRepositoryResponse {
    /** 椤圭洰浠撳簱缁戝畾 ID銆?*/
    private UUID id;

    /** 琚粦瀹氱殑 GitHub 浠撳簱闀滃儚 ID銆?*/
    private UUID repositoryId;

    /** 椤圭洰浣跨敤鐨勯粯璁ゅ垎鏀€?*/
    private String defaultBranch;

    /** 浠撳簱鍦ㄩ」鐩唴鐨勬樉绀哄悕绉般€?*/
    private String displayName;

    /** 缁戝畾鍒涘缓鏃堕棿锛孶TC銆?*/
    private LocalDateTime boundAt;
}
