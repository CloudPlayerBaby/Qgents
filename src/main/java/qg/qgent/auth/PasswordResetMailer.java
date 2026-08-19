package qg.qgent.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import jakarta.mail.internet.MimeMessage;

/**
 * 发送密码重置邮件（6 位数字验证码）。
 * <p>
 * 验证码 30 分钟内有效，用于重置密码时校验邮箱真实归属；与注册验证码（
 * {@link VerificationCodeMailer}）同模式，仅主题与引导文案不同。异步发送；
 * 发送失败只记录固定事件，不暴露验证码明文到日志。
 */
@Component
public class PasswordResetMailer {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);
    private final JavaMailSender sender;
    private final String from;

    public PasswordResetMailer(JavaMailSender sender, @Value("${spring.mail.username}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Async
    public void send(String email, String code) {
        String safeCode = HtmlUtils.htmlEscape(code);
        String html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <body style="margin:0;padding:0;background-color:#f4f6f9;font-family:'Segoe UI','PingFang SC','Microsoft YaHei',Arial,sans-serif;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="padding:32px 16px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(15,23,42,.06);">
                          <tr>
                            <td style="background:#2563eb;padding:28px 32px;color:#ffffff;">
                              <h1 style="margin:0;font-size:22px;font-weight:600;">重置你的 Qgents 密码</h1>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:32px;">
                              <p style="margin:0 0 8px;font-size:15px;color:#334155;line-height:1.7;">你正在重置 Qgents 账户密码，本次验证码为：</p>
                              <p align="center" style="margin:24px 0;font-size:36px;font-weight:700;letter-spacing:8px;color:#2563eb;">%s</p>
                              <p style="margin:0 0 24px;font-size:13px;color:#475569;line-height:1.7;">验证码 30 分钟内有效。如果你没有发起重置，请忽略此邮件，切勿将验证码告知他人。</p>
                              <hr style="border:none;border-top:1px solid #e2e8f0;margin:24px 0 16px;">
                              <p style="margin:0;font-size:12px;color:#94a3b8;">此邮件由 Qgents 自动发送，请勿直接回复。</p>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(safeCode);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Qgents 重置密码验证码");
            helper.setText(html, true);
            sender.send(message);
        } catch (MailException e) {
            // 发送失败记录异常摘要（不含验证码），便于排查 SMTP 问题。
            log.warn("Password reset email delivery failed: {}", e.getMessage());
        } catch (Exception e) {
            // MimeMessage 构造异常视为邮件组装失败，同样不暴露验证码细节。
            log.warn("Password reset email assembly failed: {}", e.getMessage());
        }
    }
}
