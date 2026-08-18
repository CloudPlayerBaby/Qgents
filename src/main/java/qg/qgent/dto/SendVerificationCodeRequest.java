package qg.qgent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册验证码发送请求：仅需邮箱，服务端校验邮箱未注册后发送 6 位验证码。
 */
@Data
public class SendVerificationCodeRequest {
    @NotBlank
    @Email
    @Size(max = 320)
    private String email;
}
