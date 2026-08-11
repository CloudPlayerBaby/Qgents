package qg.qgent.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GitHub App 瀹夎璺宠浆鍦板潃銆?
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubInstallationUrlResponse {
    /** GitHub App 瀹夎椤甸潰鍦板潃锛屽惈鐭椂鏈夋晥鐨勭鍚?state銆?*/
    private String installationUrl;

    /** 瀹夎璺宠浆鍦板潃澶辨晥鏃堕棿锛孶TC銆?*/
    private LocalDateTime expiresAt;
}
