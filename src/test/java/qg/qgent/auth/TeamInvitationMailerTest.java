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

class TeamInvitationMailerTest {
    @Test
    void mailContainsCopyButtonDomainAndPlainToken() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new jakarta.mail.internet.MimeMessage(
                jakarta.mail.Session.getInstance(new java.util.Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        TeamInvitationMailer mailer = new TeamInvitationMailer(sender, "noreply@example.com",
                "https://app.example.com");

        mailer.send("member@example.com", "opaque-token");

        ArgumentCaptor<MimeMessage> captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captured.capture());
        Object content = captured.getValue().getContent();
        assertTrue(content instanceof String);
        String html = (String) content;
        assertTrue(html.contains(">opaque-token<"));
        assertTrue(html.contains("qgCopyToken"));
        assertTrue(html.contains("href=\"https://app.example.com\""));
        assertFalse(html.contains("https://app.example.com/team-invitations/accept?token=opaque-token"));
        assertFalse(html.contains("https://app.example.com/register"));
    }
}
