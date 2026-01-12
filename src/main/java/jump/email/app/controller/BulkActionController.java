package jump.email.app.controller;

import jump.email.app.entity.EmailItem;
import jump.email.app.entity.GmailAccount;
import jump.email.app.entity.User;
import jump.email.app.repository.EmailItemRepository;
import jump.email.app.service.GmailApiService;
import jump.email.app.service.OAuthTokenService;
import jump.email.app.service.TokenRefreshService;
import jump.email.app.service.UnsubscribeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
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

    public BulkActionController(
            EmailItemRepository emailItemRepository,
            GmailApiService gmailApiService,
            OAuthTokenService oauthTokenService,
            UnsubscribeService unsubscribeService,
            TokenRefreshService tokenRefreshService) {
        this.emailItemRepository = emailItemRepository;
        this.gmailApiService = gmailApiService;
        this.oauthTokenService = oauthTokenService;
        this.unsubscribeService = unsubscribeService;
        this.tokenRefreshService = tokenRefreshService;
    }

    @PostMapping("/bulk/delete")
    public String deleteEmails(@RequestParam("emailIds") String emailIdsStr, Authentication authentication) {
        User user = oauthTokenService.getOrCreateUser(authentication);
        
        List<String> emailIds = parseEmailIds(emailIdsStr);
        
        for (String emailId : emailIds) {
            EmailItem emailItem = emailItemRepository.findById(emailId).orElse(null);
            if (emailItem != null && emailItem.getGmailAccount().getUser().getId().equals(user.getId())) {
                GmailAccount account = emailItem.getGmailAccount();
                if (account.getToken() != null && account.getToken().getAccessToken() != null) {
                    try {
                        // Ensure access token is valid (refresh if needed)
                        String accessToken = tokenRefreshService.ensureValidAccessToken(account);
                        
                        // Delete from Gmail
                        gmailApiService.deleteEmail(
                            accessToken, 
                            account.getEmailAddress(), 
                            emailItem.getGmailId()
                        );
                        // Delete from database
                        emailItemRepository.delete(emailItem);
                    } catch (Exception e) {
                        System.err.println("Error deleting email " + emailId + ": " + e.getMessage());
                    }
                }
            }
        }
        
        String categoryId = null;
        if (!emailIds.isEmpty()) {
            EmailItem firstEmail = emailItemRepository.findById(emailIds.get(0)).orElse(null);
            if (firstEmail != null && firstEmail.getCategory() != null) {
                categoryId = firstEmail.getCategory().getId();
            }
        }
        
        if (categoryId != null) {
            return "redirect:/category/" + categoryId;
        }
        return "redirect:/home";
    }

    @PostMapping("/bulk/unsubscribe")
    public String unsubscribeEmails(@RequestParam("emailIds") String emailIdsStr, Authentication authentication) {
        User user = oauthTokenService.getOrCreateUser(authentication);
        
        List<String> emailIds = parseEmailIds(emailIdsStr);
        
        try {
            unsubscribeService.unsubscribeFromEmails(emailIds, user.getId());
        } catch (Exception e) {
            log.error("Error unsubscribing from emails: {}", e.getMessage(), e);
        }
        
        String categoryId = null;
        if (!emailIds.isEmpty()) {
            EmailItem firstEmail = emailItemRepository.findById(emailIds.get(0)).orElse(null);
            if (firstEmail != null && firstEmail.getCategory() != null) {
                categoryId = firstEmail.getCategory().getId();
            }
        }
        
        if (categoryId != null) {
            return "redirect:/category/" + categoryId;
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
