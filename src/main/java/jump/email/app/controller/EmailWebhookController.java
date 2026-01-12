package jump.email.app.controller;

import jump.email.app.entity.User;
import jump.email.app.repository.UserRepository;
import jump.email.app.service.EmailProcessingService;
import jump.email.app.service.OAuthTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Webhook controller for real-time email processing.
 * Can be triggered manually or integrated with Gmail Push Notifications (Pub/Sub).
 */
@RestController
@RequestMapping("/api/webhooks")
public class EmailWebhookController {
    private final EmailProcessingService emailProcessingService;
    private final OAuthTokenService oauthTokenService;
    private final UserRepository userRepository;

    public EmailWebhookController(
            EmailProcessingService emailProcessingService,
            OAuthTokenService oauthTokenService,
            UserRepository userRepository) {
        this.emailProcessingService = emailProcessingService;
        this.oauthTokenService = oauthTokenService;
        this.userRepository = userRepository;
    }

    /**
     * Manual trigger endpoint for immediate email processing.
     * Can be called by authenticated users to process their emails immediately.
     */
    @PostMapping("/process-emails")
    public ResponseEntity<String> triggerEmailProcessing(Authentication authentication) {
        try {
            User user = oauthTokenService.getOrCreateUser(authentication);
            emailProcessingService.processEmailsForUserImmediate(user);
            return ResponseEntity.ok("Email processing triggered successfully");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error processing emails: " + e.getMessage());
        }
    }

    /**
     * Process emails for a specific user by userId.
     * Useful for admin operations or Pub/Sub webhooks.
     */
    @PostMapping("/process-emails/{userId}")
    public ResponseEntity<String> triggerEmailProcessingForUser(@PathVariable String userId) {
        try {
            User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
            emailProcessingService.processEmailsForUserImmediate(user);
            return ResponseEntity.ok("Email processing triggered for user: " + userId);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error processing emails: " + e.getMessage());
        }
    }
}
