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
    void mailContainsButtonLinkAndResetUrl() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new jakarta.mail.internet.MimeMessage(
                jakarta.mail.Session.getInstance(new java.util.Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        PasswordResetMailer mailer = new PasswordResetMailer(sender, "noreply@example.com",
                "https://app.example.com");

        mailer.send("member@example.com", "opaque-token");

        ArgumentCaptor<MimeMessage> captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captured.capture());
        Object content = captured.getValue().getContent();
        assertTrue(content instanceof String);
        String html = (String) content;
        assertTrue(html.contains("重置密码"));
        assertTrue(html.contains("https://app.example.com/reset-password?token=opaque-token"));
        assertFalse(html.contains("<script"));
    }
}
