package qg.qgent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    @Email
    @Size(max = 320)
    private String email;
    /** 注册邮箱验证码：先调 POST /auth/register/verification-codes 发送到邮箱，注册时必填校验。 */
    @NotBlank
    @Size(min = 6, max = 6)
    private String verificationCode;
    @NotBlank
    @Size(max = 128)
    private String passwordKeyId;
    @NotBlank
    @Size(max = 4096)
    private String password;
    @NotBlank
    @Size(max = 120)
    private String displayName;
}
