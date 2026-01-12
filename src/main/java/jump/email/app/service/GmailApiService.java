package jump.email.app.service;

import com.google.api.services.gmail.model.Message;

import java.util.List;

/**
 * Interface for Gmail API operations.
 * This abstraction allows for easier testing and potential future implementations.
 */
public interface GmailApiService {
    /**
     * Fetch new emails using Gmail History API for efficient incremental fetching.
     * @param accessToken OAuth access token
     * @param userId Gmail user ID (email address)
     * @param lastHistoryId Last processed history ID (null for initial fetch)
     * @return List of new messages
     * @throws Exception if API call fails
     */
    List<Message> fetchNewEmails(String accessToken, String userId, String lastHistoryId) throws Exception;

    /**
     * Get the current historyId for a user's mailbox.
     * @param accessToken OAuth access token
     * @param userId Gmail user ID (email address)
     * @return Current history ID as string, or null if unavailable
     * @throws Exception if API call fails
     */
    String getCurrentHistoryId(String accessToken, String userId) throws Exception;

    /**
     * Extract email content from a Gmail Message object.
     * @param message Gmail Message object
     * @return Extracted email content as string
     * @throws Exception if extraction fails
     */
    String extractEmailContent(Message message) throws Exception;

    /**
     * Archive an email in Gmail (removes INBOX label).
     * @param accessToken OAuth access token
     * @param userId Gmail user ID (email address)
     * @param messageId Gmail message ID
     * @throws Exception if API call fails
     */
    void archiveEmail(String accessToken, String userId, String messageId) throws Exception;

    /**
     * Delete an email in Gmail.
     * @param accessToken OAuth access token
     * @param userId Gmail user ID (email address)
     * @param messageId Gmail message ID
     * @throws Exception if API call fails
     */
    void deleteEmail(String accessToken, String userId, String messageId) throws Exception;

    /**
     * Get a single email by ID.
     * @param accessToken OAuth access token
     * @param userId Gmail user ID (email address)
     * @param messageId Gmail message ID
     * @return Gmail Message object
     * @throws Exception if API call fails
     */
    Message getEmail(String accessToken, String userId, String messageId) throws Exception;
}
