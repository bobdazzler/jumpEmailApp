package jump.email.app.controller;

import jump.email.app.entity.EmailItem;
import jump.email.app.entity.GmailAccount;
import jump.email.app.entity.User;
import jump.email.app.repository.EmailItemRepository;
import jump.email.app.repository.GmailAccountRepository;
import jump.email.app.service.GmailApiService;
import jump.email.app.service.OAuthTokenService;
import jump.email.app.service.TokenRefreshService;
import jump.email.app.service.UnsubscribeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Controller
public class BulkActionController {
    private final EmailItemRepository emailItemRepository;
    private final GmailApiService gmailApiService;
    private final OAuthTokenService oauthTokenService;
    private final UnsubscribeService unsubscribeService;
    private final TokenRefreshService tokenRefreshService;
    private final GmailAccountRepository gmailAccountRepository;

    public BulkActionController(
            EmailItemRepository emailItemRepository,
            GmailApiService gmailApiService,
            OAuthTokenService oauthTokenService,
            UnsubscribeService unsubscribeService,
            TokenRefreshService tokenRefreshService,
            GmailAccountRepository gmailAccountRepository) {
        this.emailItemRepository = emailItemRepository;
        this.gmailApiService = gmailApiService;
        this.oauthTokenService = oauthTokenService;
        this.unsubscribeService = unsubscribeService;
        this.tokenRefreshService = tokenRefreshService;
        this.gmailAccountRepository = gmailAccountRepository;
    }

    @PostMapping("/bulk/delete")
    @Transactional
    public String deleteEmails(@RequestParam("emailIds") String emailIdsStr, Authentication authentication) {
        User user = oauthTokenService.getOrCreateUser(authentication);
        
        List<String> emailIds = parseEmailIds(emailIdsStr);
        
        if (emailIds.isEmpty()) {
            log.warn("No email IDs provided for deletion");
            return "redirect:/home";
        }
        
        // Store category ID before deletion (needed for redirect)
        // Use JOIN FETCH to eagerly load category relationship
        String categoryId = null;
        try {
            EmailItem firstEmail = emailItemRepository.findByIdWithAccountAndUser(emailIds.get(0)).orElse(null);
            if (firstEmail != null && firstEmail.getCategory() != null) {
                categoryId = firstEmail.getCategory().getId();
            }
        } catch (Exception e) {
            log.warn("Could not retrieve category ID before deletion: {}", e.getMessage());
        }
        
        int successCount = 0;
        int failureCount = 0;
        
        for (String emailId : emailIds) {
            // Use JOIN FETCH to eagerly load relationships and avoid LazyInitializationException
            EmailItem emailItem = emailItemRepository.findByIdWithAccountAndUser(emailId).orElse(null);
            
            if (emailItem == null) {
                log.warn("Email item not found: {}", emailId);
                failureCount++;
                continue;
            }
            
            // Relationships are already loaded via JOIN FETCH
            GmailAccount account = emailItem.getGmailAccount();
            String accountUserId = account.getUser().getId();
            
            // Verify ownership
            if (!accountUserId.equals(user.getId())) {
                log.warn("User {} attempted to delete email {} belonging to another user", user.getId(), emailId);
                failureCount++;
                continue;
            }
            
            // Check if account has valid token
            if (account.getToken() == null || account.getToken().getAccessToken() == null) {
                log.warn("No valid access token for account {}, cannot delete email {}", account.getEmailAddress(), emailId);
                failureCount++;
                continue;
            }
            
            try {
                // Ensure access token is valid (refresh if needed)
                String accessToken = tokenRefreshService.ensureValidAccessToken(account);
                
                // Delete from Gmail first
                log.info("Deleting email {} from Gmail account {}", emailItem.getGmailId(), account.getEmailAddress());
                gmailApiService.deleteEmail(
                    accessToken, 
                    account.getEmailAddress(), 
                    emailItem.getGmailId()
                );
                
                // Only delete from database if Gmail deletion succeeded
                emailItemRepository.delete(emailItem);
                successCount++;
                log.info("Successfully deleted email {} from Gmail and database", emailId);
                
            } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                // Handle 401 Unauthorized - try refreshing token once
                if (e.getStatusCode() == 401) {
                    try {
                        log.info("Received 401 when deleting email {}, refreshing token and retrying", emailId);
                        // Reload account from repository to get latest state
                        account = gmailAccountRepository.findById(account.getId())
                            .orElseThrow(() -> new RuntimeException("Account not found"));
                        String refreshedToken = tokenRefreshService.refreshTokenOn401(account);
                        
                        // Retry deletion
                        gmailApiService.deleteEmail(refreshedToken, account.getEmailAddress(), emailItem.getGmailId());
                        emailItemRepository.delete(emailItem);
                        successCount++;
                        log.info("Successfully deleted email {} after token refresh", emailId);
                    } catch (Exception retryException) {
                        log.error("Failed to delete email {} after token refresh: {}", emailId, retryException.getMessage(), retryException);
                        failureCount++;
                    }
                } else {
                    log.error("Gmail API error deleting email {}: {} - {}", emailId, e.getStatusCode(), e.getMessage());
                    failureCount++;
                }
            } catch (Exception e) {
                log.error("Error deleting email {}: {}", emailId, e.getMessage(), e);
                failureCount++;
            }
        }
        
        log.info("Delete operation completed: {} succeeded, {} failed out of {} total", 
                successCount, failureCount, emailIds.size());
        
        // Redirect with status message
        if (categoryId != null) {
            if (failureCount == 0) {
                return "redirect:/category/" + categoryId + "?delete=success&count=" + successCount;
            } else if (successCount > 0) {
                return "redirect:/category/" + categoryId + "?delete=partial&success=" + successCount + "&failed=" + failureCount;
            } else {
                return "redirect:/category/" + categoryId + "?delete=failed";
            }
        }
        return "redirect:/home?delete=completed&success=" + successCount + "&failed=" + failureCount;
    }

    @PostMapping("/bulk/unsubscribe")
    public String unsubscribeEmails(@RequestParam("emailIds") String emailIdsStr, Authentication authentication) {
        User user = oauthTokenService.getOrCreateUser(authentication);
        
        List<String> emailIds = parseEmailIds(emailIdsStr);
        
        if (emailIds.isEmpty()) {
            log.warn("No email IDs provided for unsubscribe");
            return "redirect:/home";
        }
        
        try {
            log.info("Starting unsubscribe process for {} email(s)", emailIds.size());
            unsubscribeService.unsubscribeFromEmails(emailIds, user.getId());
            log.info("Completed unsubscribe process for {} email(s)", emailIds.size());
        } catch (Exception e) {
            log.error("Error unsubscribing from emails: {}", e.getMessage(), e);
        }
        
        // Redirect back to category view
        // Use JOIN FETCH to eagerly load category relationship
        String categoryId = null;
        try {
            EmailItem firstEmail = emailItemRepository.findByIdWithAccountAndUser(emailIds.get(0)).orElse(null);
            if (firstEmail != null && firstEmail.getCategory() != null) {
                categoryId = firstEmail.getCategory().getId();
            }
        } catch (Exception e) {
            log.warn("Could not retrieve category ID for redirect: {}", e.getMessage());
        }
        
        if (categoryId != null) {
            return "redirect:/category/" + categoryId + "?unsubscribe=processing";
        }
        return "redirect:/home";
    }

    private List<String> parseEmailIds(String emailIdsStr) {
        if (emailIdsStr == null || emailIdsStr.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(emailIdsStr.split(","))
            .map(String::trim)
            .collect(Collectors.toList());
    }
}
