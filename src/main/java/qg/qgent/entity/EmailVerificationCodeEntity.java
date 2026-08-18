package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 注册邮箱验证码记录：注册前向邮箱发送 6 位数字验证码，注册时校验通过才创建账号。
 * 仅保存验证码 SHA-256 哈希，禁止存储明文；每条记录一次性使用，可重复请求（旧记录失效）。
 */
@Data
@TableName("email_verification_codes")
public class EmailVerificationCodeEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 归一化邮箱（小写）。 */
    private String email;
    /** 验证码 SHA-256 哈希，禁止存储明文。 */
    private byte[] codeHash;
    /** 验证码过期时间（UTC）。 */
    private LocalDateTime expiresAt;
    /** 验证码使用时间（UTC），为空表示未使用。 */
    private LocalDateTime usedAt;
    /** 发送时间（UTC）。 */
    private LocalDateTime createdAt;
}
