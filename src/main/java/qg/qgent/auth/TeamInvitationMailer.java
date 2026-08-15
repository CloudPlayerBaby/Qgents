package qg.qgent.auth;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * 发送团队邀请邮件。
 * <p>
 * 邀请令牌通过两种方式提供：可复制的令牌框（按钮尝试复制，令牌文本始终可手动选中）
 * 和前往 Qgents 的域名入口。接受页未完成前，邮件不拼接深链；未注册用户登录即被引导注册。
 */
@Component
public class TeamInvitationMailer {
    private static final Logger log = LoggerFactory.getLogger(TeamInvitationMailer.class);
    private final JavaMailSender sender;
    private final String from;
    private final String frontendUrl;

    public TeamInvitationMailer(JavaMailSender sender, @Value("${spring.mail.username}") String from,
                                @Value("${app.frontend-url}") String frontendUrl) {
        this.sender = sender;
        this.from = from;
        this.frontendUrl = frontendUrl;
    }

    public void send(String email, String token) {
        String safeUrl = HtmlUtils.htmlEscape(frontendUrl);
        String safeToken = HtmlUtils.htmlEscape(token);
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
                              <h1 style="margin:0;font-size:22px;font-weight:600;">你收到一个团队邀请</h1>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:32px;">
                              <p style="margin:0 0 16px;font-size:15px;color:#334155;line-height:1.7;">有人邀请你加入 <strong>Qgents</strong> 团队。复制下方邀请令牌，前往 Qgents 登录后（未注册会先引导注册）在"加入团队"页面粘贴即可：</p>
                              <p id="qg-inv-token" title="点击选中令牌" style="margin:0 0 12px;background:#f1f5f9;border:1px solid #e2e8f0;border-radius:6px;padding:12px 16px;font-family:Consolas,Menlo,monospace;font-size:13px;color:#0f172a;word-break:break-all;user-select:all;-webkit-user-select:all;">%s</p>
                              <button id="qg-copy-btn" type="button" onclick="qgCopyToken()" style="display:inline-block;background:#2563eb;color:#ffffff;border:none;border-radius:8px;padding:10px 28px;font-size:14px;font-weight:600;cursor:pointer;">复制令牌</button>
                              <p align="center" style="margin:28px 0 8px;">
                                <a href="%s" style="color:#2563eb;font-size:14px;text-decoration:none;">前往 Qgents →</a>
                              </p>
                              <hr style="border:none;border-top:1px solid #e2e8f0;margin:24px 0 16px;">
                              <p style="margin:0;font-size:12px;color:#94a3b8;">如果不是你本人操作，请忽略此邮件。</p>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                  <script>
                  function qgCopyToken() {
                    var t = document.getElementById('qg-inv-token');
                    var b = document.getElementById('qg-copy-btn');
                    function ok() {
                      b.textContent = '已复制';
                      setTimeout(function () { b.textContent = '复制令牌'; }, 2000);
                    }
                    function fallback() {
                      var s = window.getSelection();
                      var r = document.createRange();
                      s.removeAllRanges();
                      r.selectNodeContents(t);
                      s.addRange(r);
                      try { document.execCommand('copy'); ok(); } catch (e) { s.removeAllRanges(); }
                    }
                    if (navigator.clipboard && navigator.clipboard.writeText) {
                      navigator.clipboard.writeText(t.textContent).then(ok, fallback);
                    } else {
                      fallback();
                    }
                  }
                  </script>
                </body>
                </html>
                """.formatted(safeToken, safeUrl);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Qgents 团队邀请");
            helper.setText(html, true);
            sender.send(message);
        } catch (MailException e) {
            // 邀请已持久化，邮件失败只记录固定事件，避免邮箱和邀请令牌进入日志。
            log.warn("Team invitation email delivery failed");
        } catch (Exception e) {
            // MimeMessage 构造异常视为邮件组装失败，同样不暴露令牌细节。
            log.warn("Team invitation email assembly failed");
        }
    }
}
