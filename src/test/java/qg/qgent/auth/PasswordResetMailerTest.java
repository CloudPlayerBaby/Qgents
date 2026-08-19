package qg.qgent.auth;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetMailerTest {
    @Test
    void mailContainsSixDigitVerificationCode() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new jakarta.mail.internet.MimeMessage(
                jakarta.mail.Session.getInstance(new java.util.Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        PasswordResetMailer mailer = new PasswordResetMailer(sender, "noreply@example.com");

        mailer.send("member@example.com", "483920");

        ArgumentCaptor<MimeMessage> captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captured.capture());
        Object content = captured.getValue().getContent();
        assertTrue(content instanceof String);
        String html = (String) content;
        // 验证码邮件：展示 6 位数字，不包含重置深链
        assertTrue(html.contains("重置你的 Qgents 密码"));
        assertTrue(html.contains("483920"));
        assertFalse(html.contains("reset-password?token="));
        assertFalse(html.contains("<script"));
    }
}
