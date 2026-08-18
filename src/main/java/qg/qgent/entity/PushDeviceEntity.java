package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** 用户的移动端推送注册；接口响应永不返回加密 Token。 */
@Data
@TableName("push_devices")
public class PushDeviceEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID userId;
    private String installationId;
    private String platform;
    private String provider;
    private String tokenHash;
    private String tokenCiphertext;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
