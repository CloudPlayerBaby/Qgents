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
 * 发送密码重置邮件。
 * <p>
 * 邮件包含指向前端重置页面的深链（{@code /reset-password?token=...}），链接 30 分钟内有效，
 * 用户点击后在前端引导输入新密码。重置流程必须依赖点击链接，因此提供按钮式深链并附明文
 * 链接作为兜底。异步发送；发送失败只记录固定事件，不暴露令牌内容。
 */
@Component
public class PasswordResetMailer {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);
    private final JavaMailSender sender;
    private final String from;
    private final String frontend;

    public PasswordResetMailer(JavaMailSender sender, @Value("${spring.mail.username}") String from,
                               @Value("${app.frontend-url}") String frontend) {
        this.sender = sender;
        this.from = from;
        this.frontend = frontend;
    }

    @Async
    public void send(String email, String token) {
        String safeUrl = HtmlUtils.htmlEscape(frontend + "/reset-password?token=" + token);
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
                              <p style="margin:0 0 24px;font-size:15px;color:#334155;line-height:1.7;">我们收到了重置你 Qgents 账户密码的请求。点击下方按钮，按提示设置新密码：</p>
                              <p align="center" style="margin:0 0 24px;">
                                <a href="%s" style="display:inline-block;background:#2563eb;color:#ffffff;border-radius:8px;padding:12px 32px;font-size:15px;font-weight:600;text-decoration:none;">重置密码</a>
                              </p>
                              <p style="margin:0 0 8px;font-size:13px;color:#475569;">如果按钮无法点击，请复制下方链接到浏览器打开：</p>
                              <p style="margin:0;font-size:12px;color:#334155;word-break:break-all;">%s</p>
                              <hr style="border:none;border-top:1px solid #e2e8f0;margin:24px 0 16px;">
                              <p style="margin:0;font-size:12px;color:#94a3b8;">链接 30 分钟内有效。如果你没有请求重置密码，请忽略此邮件。</p>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(safeUrl, safeUrl);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Qgents 重置密码");
            helper.setText(html, true);
            sender.send(message);
        } catch (MailException e) {
            // 发送失败记录异常摘要（不含令牌），便于排查 SMTP 问题。
            log.warn("Password reset email delivery failed: {}", e.getMessage());
        } catch (Exception e) {
            // MimeMessage 构造异常视为邮件组装失败，同样不暴露令牌细节。
            log.warn("Password reset email assembly failed: {}", e.getMessage());
        }
    }
}
