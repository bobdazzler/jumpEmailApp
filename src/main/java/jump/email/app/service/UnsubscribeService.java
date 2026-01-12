package jump.email.app.service;

import jump.email.app.entity.EmailItem;
import jump.email.app.entity.User;
import jump.email.app.repository.EmailItemRepository;
import jump.email.app.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    public UnsubscribeService(
            EmailClassificationService emailClassificationService,
            EmailItemRepository emailItemRepository,
            UserRepository userRepository) {
        this.emailClassificationService = emailClassificationService;
        this.emailItemRepository = emailItemRepository;
        this.userRepository = userRepository;
    }

    public void unsubscribeFromEmails(List<String> emailIds, String userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        for (String emailId : emailIds) {
            EmailItem emailItem = emailItemRepository.findById(emailId)
                .orElseThrow(() -> new RuntimeException("Email not found"));
            
            if (!emailItem.getGmailAccount().getUser().getId().equals(userId)) {
                continue; // Skip emails that don't belong to user
            }
            
            try {
                // Extract unsubscribe link from email
                String unsubscribeLink = emailClassificationService.extractUnsubscribeLink(emailItem.getOriginalContent());
                
                if (unsubscribeLink != null && !unsubscribeLink.equals("NOT_FOUND") && unsubscribeLink.startsWith("http")) {
                    // Fetch the unsubscribe page
                    String htmlContent = fetchWebPage(unsubscribeLink);
                    
                    // Generate unsubscribe instructions using AI
                    String instructions = emailClassificationService.generateUnsubscribeInstructions(htmlContent);
                    
                    // In a real implementation, you would parse the instructions JSON
                    // and use Selenium or similar to automate the unsubscribe process
                    // For now, we'll just log it
                    log.info("Unsubscribe link found for email {}: {}", emailId, unsubscribeLink);
                    log.debug("Unsubscribe instructions: {}", instructions);
                    
                    // Note: Full automation would require:
                    // 1. Parse the JSON instructions
                    // 2. Use Selenium/Playwright to navigate and fill forms
                    // 3. Handle various unsubscribe page types
                    // This is a complex feature that requires browser automation
                }
                
            } catch (Exception e) {
                log.error("Error unsubscribing from email {}: {}", emailId, e.getMessage(), e);
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
