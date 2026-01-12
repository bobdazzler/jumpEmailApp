package jump.email.app.service;

import jump.email.app.entity.EmailCategory;

import java.util.List;

/**
 * Interface for email classification and summarization using AI.
 * This abstraction allows for easier testing and potential future implementations.
 */
public interface EmailClassificationService {
    /**
     * Custom exception for AI service quota/rate limit errors.
     */
    class QuotaException extends RuntimeException {
        public QuotaException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Classify an email into one of the provided categories.
     * @param emailContent The email content to classify
     * @param categories List of available categories
     * @return The name of the matched category
     * @throws QuotaException if AI service quota is exceeded
     */
    String classifyEmail(String emailContent, List<EmailCategory> categories);

    /**
     * Generate a summary of the email content.
     * @param emailContent The email content to summarize
     * @return A concise summary (2-3 sentences)
     * @throws QuotaException if AI service quota is exceeded
     */
    String summarizeEmail(String emailContent);

    /**
     * Extract unsubscribe link from email content.
     * @param emailContent The email content (HTML/text)
     * @return The unsubscribe URL, or null if not found
     * @throws QuotaException if AI service quota is exceeded
     */
    String extractUnsubscribeLink(String emailContent);

    /**
     * Generate instructions for unsubscribing from an email list.
     * @param htmlContent The unsubscribe page HTML
     * @return JSON string with unsubscribe instructions
     * @throws QuotaException if AI service quota is exceeded
     */
    String generateUnsubscribeInstructions(String htmlContent);
}
