package qg.qgent.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class TeamInvitationMailer {
    private static final Logger log = LoggerFactory.getLogger(TeamInvitationMailer.class);
    private final JavaMailSender sender;
    private final String from;
    private final String invitationUrl;
    private final String registrationUrl;

    public TeamInvitationMailer(JavaMailSender sender, @Value("${spring.mail.username}") String from,
            @Value("${app.invitation-url}") String invitationUrl,
            @Value("${app.registration-url}") String registrationUrl) {
        this.sender = sender;
        this.from = from;
        this.invitationUrl = invitationUrl;
        this.registrationUrl = registrationUrl;
    }

    public void send(String email, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Qgents 团队邀请");
        message.setText("如果你还没有 Qgents 账号，请先使用受邀邮箱注册：\n" + registrationUrl
                + "\n\n注册或登录后，请打开以下链接接受团队邀请：\n" + invitationUrl + "?token=" + token);
        try {
            sender.send(message);
        } catch (MailException e) {
            // 邀请已持久化，邮件失败只记录固定事件，避免邮箱和邀请令牌进入日志。
            log.warn("Team invitation email delivery failed");
        }
    }
}
