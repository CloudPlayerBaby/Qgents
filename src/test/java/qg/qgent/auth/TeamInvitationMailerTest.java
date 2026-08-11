package qg.qgent.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TeamInvitationMailerTest {
    @Test
    void mailContainsConfiguredRegistrationAndAcceptanceLinks() {
        JavaMailSender sender = mock(JavaMailSender.class);
        TeamInvitationMailer mailer = new TeamInvitationMailer(sender, "noreply@example.com",
                "https://app.example.com/invitations/accept", "https://app.example.com/register");

        mailer.send("member@example.com", "opaque-token");

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        String text = message.getValue().getText();
        assertTrue(text.contains("https://app.example.com/register"));
        assertTrue(text.contains("https://app.example.com/invitations/accept?token=opaque-token"));
    }
}
