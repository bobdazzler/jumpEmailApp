package jump.email.app.service;

import com.google.api.services.gmail.model.Message;
import jump.email.app.entity.EmailCategory;
import jump.email.app.entity.EmailItem;
import jump.email.app.entity.GmailAccount;
import jump.email.app.entity.User;
import jump.email.app.repository.CategoryRepository;
import jump.email.app.repository.EmailItemRepository;
import jump.email.app.repository.GmailAccountRepository;
import jump.email.app.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class EmailProcessingService {
    private final GmailApiService gmailApiService;
    private final EmailClassificationService emailClassificationService;
    private final EmailItemRepository emailItemRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final GmailAccountRepository gmailAccountRepository;
    private final TokenRefreshService tokenRefreshService;

    public EmailProcessingService(
            GmailApiService gmailApiService,
            EmailClassificationService emailClassificationService,
            EmailItemRepository emailItemRepository,
            CategoryRepository categoryRepository,
            UserRepository userRepository,
            GmailAccountRepository gmailAccountRepository,
            TokenRefreshService tokenRefreshService) {
        this.gmailApiService = gmailApiService;
        this.emailClassificationService = emailClassificationService;
        this.emailItemRepository = emailItemRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.gmailAccountRepository = gmailAccountRepository;
        this.tokenRefreshService = tokenRefreshService;
    }

    /**
     * Scheduled task runs less frequently now (every 5 minutes) since we use History API
     * for efficient incremental fetching. This serves as a backup to catch any missed emails.
     */
    @Scheduled(fixedRate = 300000) // Run every 5 minutes (backup/fallback)
    @Transactional
    public void processNewEmails() {
        log.info("processing new email started");
        try {
            List<User> users = userRepository.findAll();
            
            for (User user : users) {
                List<GmailAccount> accounts = gmailAccountRepository.findByUser(user);
                
                for (GmailAccount account : accounts) {
                    if (account.getToken() == null || account.getToken().getAccessToken() == null) {
                        continue;
                    }
                    
                    if (account.getSyncStatus() != jump.email.app.entity.SyncStatus.ACTIVE) {
                        continue;
                    }
                    
                    String emailAddress = account.getEmailAddress();
                    
                    try {
                        // Ensure access token is valid (refresh if needed)
                        String accessToken = tokenRefreshService.ensureValidAccessToken(account);
                        
                        try {
                            // Use History API with stored historyId for efficient incremental fetching
                            List<Message> newEmails = gmailApiService.fetchNewEmails(accessToken, emailAddress, account.getLastHistoryId());
                            
                            for (Message email : newEmails) {
                                processEmail(email, account, accessToken, emailAddress);
                            }
                            
                            // Update historyId after processing
                            if (!newEmails.isEmpty()) {
                                try {
                                    String newHistoryId = gmailApiService.getCurrentHistoryId(accessToken, emailAddress);
                                    if (newHistoryId != null) {
                                        account.setLastHistoryId(newHistoryId);
                                        gmailAccountRepository.save(account);
                                    }
                                } catch (Exception e) {
                                    log.error("Failed to update historyId for account {}: {}", emailAddress, e.getMessage(), e);
                                }
                            }
                        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                            // Handle 401 Unauthorized - refresh token and retry once
                            if (e.getStatusCode() == 401) {
                                log.info("Received 401 for account {}, refreshing token and retrying...", emailAddress);
                                try {
                                    // Reload account to get latest state
                                    account = gmailAccountRepository.findById(account.getId())
                                        .orElseThrow(() -> new RuntimeException("Account not found"));
                                    accessToken = tokenRefreshService.refreshTokenOn401(account);
                                    // Reload again after refresh
                                    account = gmailAccountRepository.findById(account.getId())
                                        .orElseThrow(() -> new RuntimeException("Account not found"));
                                    
                                    // Retry the operation
                                    List<Message> newEmails = gmailApiService.fetchNewEmails(accessToken, emailAddress, account.getLastHistoryId());
                                    
                                    for (Message email : newEmails) {
                                        processEmail(email, account, accessToken, emailAddress);
                                    }
                                    
                                    // Update historyId after processing
                                    if (!newEmails.isEmpty()) {
                                        try {
                                            String newHistoryId = gmailApiService.getCurrentHistoryId(accessToken, emailAddress);
                                            if (newHistoryId != null) {
                                                account.setLastHistoryId(newHistoryId);
                                                gmailAccountRepository.save(account);
                                            }
                                        } catch (Exception e2) {
                                            log.error("Failed to update historyId after retry for account {}: {}", emailAddress, e2.getMessage(), e2);
                                        }
                                    }
                                } catch (Exception retryException) {
                                    log.error("Failed to refresh token and retry for account {}: {}", emailAddress, retryException.getMessage(), retryException);
                                    throw retryException;
                                }
                            } else {
                                throw e;
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error processing emails for account {}: {}", account.getEmailAddress(), e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error in scheduled email processing: {}", e.getMessage(), e);
        }
        log.info("processing new email ended");
    }

    /**
     * Manually trigger email processing for a user (can be called from webhook/API).
     * This method uses History API for efficient fetching.
     */
    @Transactional
    public void processEmailsForUserImmediate(User user) {
        List<GmailAccount> accounts = gmailAccountRepository.findByUser(user);
        
        for (GmailAccount account : accounts) {
            if (account.getToken() == null || account.getToken().getAccessToken() == null) {
                continue;
            }
            
            if (account.getSyncStatus() != jump.email.app.entity.SyncStatus.ACTIVE) {
                continue;
            }
            
            String emailAddress = account.getEmailAddress();
            
            try {
                // Ensure access token is valid (refresh if needed)
                String accessToken = tokenRefreshService.ensureValidAccessToken(account);
                
                try {
                    List<Message> newEmails = gmailApiService.fetchNewEmails(accessToken, emailAddress, account.getLastHistoryId());
                    
                    for (Message email : newEmails) {
                        processEmail(email, account, accessToken, emailAddress);
                    }
                    
                    // Update historyId
                    if (!newEmails.isEmpty()) {
                        String newHistoryId = gmailApiService.getCurrentHistoryId(accessToken, emailAddress);
                        if (newHistoryId != null) {
                            account.setLastHistoryId(newHistoryId);
                            gmailAccountRepository.save(account);
                        }
                    }
                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                    // Handle 401 Unauthorized - refresh token and retry once
                    if (e.getStatusCode() == 401) {
                        log.info("Received 401 for account {}, refreshing token and retrying...", emailAddress);
                        // Reload account to get latest state
                        account = gmailAccountRepository.findById(account.getId())
                            .orElseThrow(() -> new RuntimeException("Account not found"));
                        accessToken = tokenRefreshService.refreshTokenOn401(account);
                        // Reload again after refresh
                        account = gmailAccountRepository.findById(account.getId())
                            .orElseThrow(() -> new RuntimeException("Account not found"));
                        
                        // Retry the operation
                        List<Message> newEmails = gmailApiService.fetchNewEmails(accessToken, emailAddress, account.getLastHistoryId());
                        
                        for (Message email : newEmails) {
                            processEmail(email, account, accessToken, emailAddress);
                        }
                        
                        // Update historyId
                        if (!newEmails.isEmpty()) {
                            String newHistoryId = gmailApiService.getCurrentHistoryId(accessToken, emailAddress);
                            if (newHistoryId != null) {
                                account.setLastHistoryId(newHistoryId);
                                gmailAccountRepository.save(account);
                            }
                        }
                    } else {
                        throw e;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Error processing emails for account " + account.getEmailAddress() + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Gets or creates the "Unsorted Emails" category for an account.
     * This category is used as a fallback when AI processing fails or quota is exceeded.
     */
    private EmailCategory getOrCreateUnsortedEmailsCategory(GmailAccount account) {
        String unsortedCategoryName = "Unsorted Emails";
        
        // Try to find existing "Unsorted Emails" category
        return categoryRepository.findByGmailAccountAndNameIgnoreCase(account, unsortedCategoryName)
            .orElseGet(() -> {
                // Create it if it doesn't exist
                EmailCategory unsortedCategory = new EmailCategory();
                unsortedCategory.setName(unsortedCategoryName);
                unsortedCategory.setDescription("Emails that couldn't be automatically categorized due to AI quota limits or processing errors");
                unsortedCategory.setGmailAccount(account);
                log.info("Creating 'Unsorted Emails' category for account: {}", account.getEmailAddress());
                return categoryRepository.save(unsortedCategory);
            });
    }

    private void processEmail(Message email, GmailAccount account, String accessToken, String gmailUserId) {
        try {
            String messageId = email.getId();
            
            // Check if email already processed
            EmailItem existing = emailItemRepository.findByGmailIdAndGmailAccount(messageId, account);
            if (existing != null) {
                return;
            }
            
            // Extract email content
            String emailContent = gmailApiService.extractEmailContent(email);
            
            // Get account's categories
            List<EmailCategory> categories = categoryRepository.findByGmailAccount(account);
            if (categories.isEmpty()) {
                return; // Skip if no categories defined
            }
            
            EmailCategory matchedCategory;
            String summary;
            
            try {
                // Classify email using AI
                String categoryName = emailClassificationService.classifyEmail(emailContent, categories);
                
                // Find matching category
                matchedCategory = categories.stream()
                    .filter(cat -> cat.getName().equalsIgnoreCase(categoryName.trim()))
                    .findFirst()
                    .orElse(null);
                
                if (matchedCategory == null) {
                    // If AI doesn't match exactly, use "Unsorted Emails" category as fallback
                    log.warn("AI classification '{}' didn't match any category for email {}, using Unsorted Emails", 
                            categoryName, messageId);
                    matchedCategory = getOrCreateUnsortedEmailsCategory(account);
                    summary = "AI classified as '" + categoryName + "' but no matching category found. Click to view original content.";
                } else {
                    // Summarize email using AI
                    summary = emailClassificationService.summarizeEmail(emailContent);
                }
            } catch (EmailClassificationService.QuotaException e) {
                // OpenAI quota exceeded - skip AI processing but still save the email
                log.warn("OpenAI quota exceeded, skipping AI processing for email {}. Email will be saved to Unsorted Emails category.", messageId);
                // Use "Unsorted Emails" category as fallback
                matchedCategory = getOrCreateUnsortedEmailsCategory(account);
                summary = "AI processing skipped due to quota limit. Click to view original content.";
                // Continue to save the email
            }
            
            // Save email item
            EmailItem emailItem = new EmailItem();
            emailItem.setGmailId(messageId);
            emailItem.setSummary(summary);
            emailItem.setOriginalContent(emailContent);
            emailItem.setCategory(matchedCategory);
            emailItem.setGmailAccount(account);
            emailItemRepository.save(emailItem);
            
            // Archive email in Gmail
            gmailApiService.archiveEmail(accessToken, gmailUserId, messageId);
            
        } catch (EmailClassificationService.QuotaException e) {
            // If quota error occurs during email extraction or other operations, still save to Unsorted Emails
            try {
                String emailContent = gmailApiService.extractEmailContent(email);
                EmailCategory unsortedCategory = getOrCreateUnsortedEmailsCategory(account);
                EmailItem emailItem = new EmailItem();
                emailItem.setGmailId(email.getId());
                emailItem.setSummary("AI processing failed due to quota limit. Click to view original content.");
                emailItem.setOriginalContent(emailContent);
                emailItem.setCategory(unsortedCategory);
                emailItem.setGmailAccount(account);
                emailItemRepository.save(emailItem);
                log.warn("OpenAI quota exceeded for email {}, saved to Unsorted Emails category", email.getId());
            } catch (Exception saveException) {
                log.error("Failed to save email {} to Unsorted Emails category: {}", email.getId(), saveException.getMessage(), saveException);
            }
        } catch (Exception e) {
            // For any other error, try to save to Unsorted Emails category
            try {
                String emailContent = gmailApiService.extractEmailContent(email);
                EmailCategory unsortedCategory = getOrCreateUnsortedEmailsCategory(account);
                EmailItem emailItem = new EmailItem();
                emailItem.setGmailId(email.getId());
                emailItem.setSummary("Email processing failed: " + e.getMessage() + ". Click to view original content.");
                emailItem.setOriginalContent(emailContent);
                emailItem.setCategory(unsortedCategory);
                emailItem.setGmailAccount(account);
                emailItemRepository.save(emailItem);
                log.warn("Error processing email {}, saved to Unsorted Emails category: {}", email.getId(), e.getMessage());
            } catch (Exception saveException) {
                log.error("Failed to save email {} to Unsorted Emails category after error: {}", email.getId(), saveException.getMessage(), saveException);
            }
        }
    }

}
