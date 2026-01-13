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

    @Transactional
    public void unsubscribeFromEmails(List<String> emailIds, String userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        String userEmail = user.getPrimaryEmail();
        
        for (String emailId : emailIds) {
            EmailItem emailItem = emailItemRepository.findById(emailId)
                .orElseThrow(() -> new RuntimeException("Email not found"));
            
            if (!emailItem.getGmailAccount().getUser().getId().equals(userId)) {
                continue; // Skip emails that don't belong to user
            }
            
            // Skip if already unsubscribed
            if (emailItem.getUnsubscribed() != null && emailItem.getUnsubscribed()) {
                log.info("Email {} already unsubscribed, skipping", emailId);
                continue;
            }
            
            try {
                // Extract unsubscribe link from email
                String unsubscribeLink = emailClassificationService.extractUnsubscribeLink(emailItem.getOriginalContent());
                
                if (unsubscribeLink == null || unsubscribeLink.equals("NOT_FOUND") || !unsubscribeLink.startsWith("http")) {
                    log.warn("No valid unsubscribe link found for email {}", emailId);
                    emailItem.setUnsubscribeStatus("NOT_FOUND");
                    emailItemRepository.save(emailItem);
                    continue;
                }
                
                // Store the unsubscribe link
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
                
                // Update email item with result
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
                emailItemRepository.save(emailItem);
            } catch (Exception e) {
                log.error("Error unsubscribing from email {}: {}", emailId, e.getMessage(), e);
                emailItem.setUnsubscribeStatus("FAILED");
                emailItemRepository.save(emailItem);
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
