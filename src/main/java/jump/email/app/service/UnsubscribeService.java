package jump.email.app.service;

import jump.email.app.entity.EmailItem;
import jump.email.app.entity.User;
import jump.email.app.repository.EmailItemRepository;
import jump.email.app.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Service
public class UnsubscribeService {
    private final EmailClassificationService emailClassificationService;
    private final EmailItemRepository emailItemRepository;
    private final UserRepository userRepository;
    private final UnsubscribeAutomationService unsubscribeAutomationService;

    public UnsubscribeService(
            EmailClassificationService emailClassificationService,
            EmailItemRepository emailItemRepository,
            UserRepository userRepository,
            UnsubscribeAutomationService unsubscribeAutomationService) {
        this.emailClassificationService = emailClassificationService;
        this.emailItemRepository = emailItemRepository;
        this.userRepository = userRepository;
        this.unsubscribeAutomationService = unsubscribeAutomationService;
    }

    /**
     * Process unsubscribe for multiple emails.
     * Each email is processed individually - failures won't stop the batch.
     */
    public void unsubscribeFromEmails(List<String> emailIds, String userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        String userEmail = user.getPrimaryEmail();
        
        // Process each email individually - failures won't stop the batch
        for (String emailId : emailIds) {
            try {
                processSingleUnsubscribe(emailId, userId, userEmail);
            } catch (Exception e) {
                log.error("Failed to process unsubscribe for email {}: {}", emailId, e.getMessage(), e);
            }
        }
    }
    
    /**
     * Process unsubscribe for a single email in its own transaction.
     * If this method fails, it will be logged but won't affect other emails in the batch.
     */
    @Transactional
    public void processSingleUnsubscribe(String emailId, String userId, String userEmail) {
        EmailItem emailItem = emailItemRepository.findById(emailId)
            .orElseThrow(() -> new RuntimeException("Email not found: " + emailId));
        
        // Verify ownership
        if (!emailItem.getGmailAccount().getUser().getId().equals(userId)) {
            log.warn("User {} attempted to unsubscribe from email {} belonging to another user", userId, emailId);
            return; // Skip emails that don't belong to user
        }
        
        // Skip if already unsubscribed
        if (emailItem.getUnsubscribed() != null && emailItem.getUnsubscribed()) {
            log.info("Email {} already unsubscribed, skipping", emailId);
            return;
        }
        
        try {
            // Extract unsubscribe link from email
            String unsubscribeLink = emailClassificationService.extractUnsubscribeLink(emailItem.getOriginalContent());
            
            if (unsubscribeLink == null || unsubscribeLink.equals("NOT_FOUND") || !unsubscribeLink.startsWith("http")) {
                log.warn("No valid unsubscribe link found for email {}", emailId);
                emailItem.setUnsubscribeStatus("NOT_FOUND");
                emailItemRepository.save(emailItem);
                return;
            }
            
            // Store the unsubscribe link and set status to PENDING
            emailItem.setUnsubscribeLink(unsubscribeLink);
            emailItem.setUnsubscribeStatus("PENDING");
            emailItemRepository.save(emailItem);
            
            log.info("Processing unsubscribe for email {}: {}", emailId, unsubscribeLink);
            
            // Fetch the unsubscribe page HTML
            String htmlContent = fetchWebPage(unsubscribeLink);
            
            // Generate unsubscribe instructions using AI
            String instructionsJson = emailClassificationService.generateUnsubscribeInstructions(htmlContent);
            
            log.debug("Unsubscribe instructions for email {}: {}", emailId, instructionsJson);
            
            // Execute the unsubscribe automation
            boolean success = unsubscribeAutomationService.executeUnsubscribe(
                unsubscribeLink, 
                instructionsJson, 
                userEmail
            );
            
            // Update email item with result (always save status, even on failure)
            if (success) {
                emailItem.setUnsubscribed(true);
                emailItem.setUnsubscribeStatus("SUCCESS");
                log.info("Successfully unsubscribed from email {}", emailId);
            } else {
                emailItem.setUnsubscribed(false);
                emailItem.setUnsubscribeStatus("FAILED");
                log.warn("Failed to unsubscribe from email {}", emailId);
            }
            
            emailItemRepository.save(emailItem);
            
        } catch (EmailClassificationService.QuotaException e) {
            log.warn("AI quota exceeded while processing unsubscribe for email {}: {}", emailId, e.getMessage());
            emailItem.setUnsubscribeStatus("FAILED");
            emailItem.setUnsubscribed(false);
            emailItemRepository.save(emailItem);
        } catch (Exception e) {
            log.error("Error unsubscribing from email {}: {}", emailId, e.getMessage(), e);
            // Always save the failure status - don't let exceptions prevent status updates
            try {
                emailItem.setUnsubscribeStatus("FAILED");
                emailItem.setUnsubscribed(false);
                emailItemRepository.save(emailItem);
            } catch (Exception saveException) {
                log.error("Failed to save unsubscribe status for email {}: {}", emailId, saveException.getMessage(), saveException);
            }
        }
    }

    private String fetchWebPage(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
            );
            
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
            reader.close();
            
            return response.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error fetching web page: " + e.getMessage(), e);
        }
    }
}
