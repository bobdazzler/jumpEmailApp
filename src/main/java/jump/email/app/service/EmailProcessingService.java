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
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    private final DistributedLockService distributedLockService;
    
    // Rate limiting: Gmail API allows 250 quota units per user per second
    // Each API call costs ~5-10 units, so we limit to ~20-30 calls per second per account
    private static final long RATE_LIMIT_DELAY_MS = 50; // ~20 calls per second
    private static final int BATCH_SIZE = 10; // Process emails in batches of 10

    public EmailProcessingService(
            GmailApiService gmailApiService,
            EmailClassificationService emailClassificationService,
            EmailItemRepository emailItemRepository,
            CategoryRepository categoryRepository,
            UserRepository userRepository,
            GmailAccountRepository gmailAccountRepository,
            TokenRefreshService tokenRefreshService,
            DistributedLockService distributedLockService) {
        this.gmailApiService = gmailApiService;
        this.emailClassificationService = emailClassificationService;
        this.emailItemRepository = emailItemRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.gmailAccountRepository = gmailAccountRepository;
        this.tokenRefreshService = tokenRefreshService;
        this.distributedLockService = distributedLockService;
    }

    /**
     * Scheduled task runs less frequently now (every 5 minutes) since we use History API
     * for efficient incremental fetching. This serves as a backup to catch any missed emails.
     * 
     * Now with:
     * - Async processing per account
     * - Distributed locking for multi-node safety
     * - Rate limiting
     * - Batch processing
     */
    @Scheduled(fixedRate = 300000) // Run every 5 minutes (backup/fallback)
    public void processNewEmails() {
        log.info("Processing new emails started");
        try {
            List<User> users = userRepository.findAll();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (User user : users) {
                List<GmailAccount> accounts = gmailAccountRepository.findByUser(user);
                String primaryEmail = user.getPrimaryEmail();
                log.info("Fetching emails for user {}", primaryEmail);
                
                for (GmailAccount account : accounts) {
                    // Process each account asynchronously
                    CompletableFuture<Void> future = processAccountEmailsAsync(account);
                    futures.add(future);
                }
            }
            
            // Wait for all async tasks to complete (with timeout)
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .exceptionally(ex -> {
                    log.error("Error in async email processing: {}", ex.getMessage(), ex);
                    return null;
                })
                .join();
                
        } catch (Exception e) {
            log.error("Error in scheduled email processing: {}", e.getMessage(), e);
        }
        log.info("Processing new emails ended");
    }
    
    /**
     * Process emails for a single account asynchronously.
     * Uses distributed locking to ensure only one node processes an account at a time.
     */
    @Async("emailProcessingExecutor")
    public CompletableFuture<Void> processAccountEmailsAsync(GmailAccount account) {
        String accountId = account.getId();
        String nodeId = distributedLockService.getNodeId();
        
        // Try to acquire distributed lock
        if (!distributedLockService.tryLock(accountId, nodeId)) {
            log.debug("Account {} is already being processed by another node, skipping", account.getEmailAddress());
            return CompletableFuture.completedFuture(null);
        }
        
        try {
            processAccountEmails(account);
        } finally {
            // Always release the lock
            distributedLockService.releaseLock(accountId, nodeId);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Process emails for a single account with rate limiting and batching.
     */
    private void processAccountEmails(GmailAccount account) {
        if (account.getToken() == null || account.getToken().getAccessToken() == null) {
            log.debug("Account {} has no valid token, skipping", account.getEmailAddress());
            return;
        }
        
        if (account.getSyncStatus() != jump.email.app.entity.SyncStatus.ACTIVE) {
            log.debug("Account {} is not active, skipping", account.getEmailAddress());
            return;
        }
        
        String emailAddress = account.getEmailAddress();
        
        try {
            // Ensure access token is valid (refresh if needed)
            String accessToken = tokenRefreshService.ensureValidAccessToken(account);
            
            // Rate limit: delay before API call
            Thread.sleep(RATE_LIMIT_DELAY_MS);
            
            try {
                // Use History API with stored historyId for efficient incremental fetching
                List<Message> newEmails = gmailApiService.fetchNewEmails(accessToken, emailAddress, account.getLastHistoryId());
                log.info("Emails to process count {} for {}", newEmails.size(), emailAddress);
                
                if (newEmails.isEmpty()) {
                    return;
                }
                
                // Process emails in batches with rate limiting
                processEmailsInBatches(newEmails, account, accessToken, emailAddress);
                
                // Update historyId after processing
                try {
                    Thread.sleep(RATE_LIMIT_DELAY_MS); // Rate limit before historyId call
                    String newHistoryId = gmailApiService.getCurrentHistoryId(accessToken, emailAddress);
                    if (newHistoryId != null) {
                        account.setLastHistoryId(newHistoryId);
                        gmailAccountRepository.save(account);
                    }
                } catch (Exception e) {
                    log.error("Failed to update historyId for account {}: {}", emailAddress, e.getMessage(), e);
                }
                
            } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                // Handle 401 Unauthorized - refresh token and retry once
                if (e.getStatusCode() == 401) {
                    log.info("Received 401 for account {}, refreshing token and retrying...", emailAddress);
                    handle401Error(account, emailAddress);
                } else {
                    log.error("Gmail API error for account {}: {} - {}", emailAddress, e.getStatusCode(), e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Email processing interrupted for account {}", emailAddress);
        } catch (Exception e) {
            log.error("Error processing emails for account {}: {}", account.getEmailAddress(), e.getMessage(), e);
        }
    }
    
    /**
     * Process emails in batches with rate limiting.
     */
    private void processEmailsInBatches(List<Message> emails, GmailAccount account, String accessToken, String emailAddress) {
        for (int i = 0; i < emails.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, emails.size());
            List<Message> batch = emails.subList(i, endIndex);
            
            log.debug("Processing batch {}-{} of {} emails for account {}", 
                    i + 1, endIndex, emails.size(), emailAddress);
            
            for (Message email : batch) {
                try {
                    // Rate limit: delay between email processing
                    Thread.sleep(RATE_LIMIT_DELAY_MS);
                    
                    processSingleEmail(email, account, accessToken, emailAddress);
                } catch (Exception e) {
                    log.error("Failed to process email {} for account {}: {}", 
                            email.getId(), emailAddress, e.getMessage(), e);
                    // Continue with next email - no rollback
                }
            }
            
            // Small delay between batches
            try {
                Thread.sleep(RATE_LIMIT_DELAY_MS * 2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Batch processing interrupted");
                break;
            }
        }
    }
    
    /**
     * Handle 401 Unauthorized error by refreshing token and retrying.
     */
    private void handle401Error(GmailAccount account, String emailAddress) {
        try {
            // Reload account to get latest state
            account = gmailAccountRepository.findById(account.getId())
                .orElseThrow(() -> new RuntimeException("Account not found"));
            String accessToken = tokenRefreshService.refreshTokenOn401(account);
            
            // Reload again after refresh
            account = gmailAccountRepository.findById(account.getId())
                .orElseThrow(() -> new RuntimeException("Account not found"));
            
            // Rate limit before retry
            Thread.sleep(RATE_LIMIT_DELAY_MS);
            
            // Retry the operation
            List<Message> newEmails = gmailApiService.fetchNewEmails(accessToken, emailAddress, account.getLastHistoryId());
            
            // Process emails in batches
            processEmailsInBatches(newEmails, account, accessToken, emailAddress);
            
            // Update historyId after processing
            if (!newEmails.isEmpty()) {
                try {
                    Thread.sleep(RATE_LIMIT_DELAY_MS);
                    String newHistoryId = gmailApiService.getCurrentHistoryId(accessToken, emailAddress);
                    if (newHistoryId != null) {
                        account.setLastHistoryId(newHistoryId);
                        gmailAccountRepository.save(account);
                    }
                } catch (Exception e2) {
                    log.error("Failed to update historyId after retry for account {}: {}", emailAddress, e2.getMessage(), e2);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Token refresh retry interrupted for account {}", emailAddress);
        } catch (Exception retryException) {
            log.error("Failed to refresh token and retry for account {}: {}", emailAddress, retryException.getMessage(), retryException);
        }
    }

    /**
     * Manually trigger email processing for a user (can be called from webhook/API).
     * This method uses History API for efficient fetching.
     * Uses async processing with distributed locking for multi-node safety.
     */
    public void processEmailsForUserImmediate(User user) {
        List<GmailAccount> accounts = gmailAccountRepository.findByUser(user);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (GmailAccount account : accounts) {
            // Process each account asynchronously
            CompletableFuture<Void> future = processAccountEmailsAsync(account);
            futures.add(future);
        }
        
        // Wait for all async tasks to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .exceptionally(ex -> {
                log.error("Error in async email processing for user {}: {}", user.getId(), ex.getMessage(), ex);
                return null;
            })
            .join();
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

    /**
     * Process a single email in its own transaction.
     * If this method fails, it will be logged but won't affect other emails in the batch.
     */
    @Transactional
    public void processSingleEmail(Message email, GmailAccount account, String accessToken, String gmailUserId) {
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
