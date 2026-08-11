package qg.qgent.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 发送密码重置邮件
 * PasswordResetMailer
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
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Qgents 重置密码");
        message.setText("请在30分钟内打开以下链接重置密码：\n" + frontend + "/reset-password?token=" + token + "\n如非本人操作，请忽略此邮件。");
        try {
            sender.send(message);
        } catch (MailException e) {
            log.warn("Password reset email delivery failed");
        }
    }
}
