package jump.email.app.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class GmailService implements GmailApiService {
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "Jump Email Sorter";
    
    private final NetHttpTransport httpTransport;
    
    public GmailService() throws Exception {
        this.httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    }

    public Gmail getGmailService(String accessToken) throws Exception {
        Credential credential = new Credential.Builder(
            com.google.api.client.auth.oauth2.BearerToken.authorizationHeaderAccessMethod())
            .setTransport(httpTransport)
            .setJsonFactory(JSON_FACTORY)
            .build();
        credential.setAccessToken(accessToken);
        
        return new Gmail.Builder(httpTransport, JSON_FACTORY, credential)
            .setApplicationName(APPLICATION_NAME)
            .build();
    }

    public List<Message> fetchNewEmails(String accessToken, String userId) throws Exception {
        return fetchNewEmails(accessToken, userId, null);
    }

    /**
     * Fetch new emails using Gmail History API for efficient incremental fetching.
     * If lastHistoryId is provided, only fetches emails changed since that point.
     * Otherwise, fetches unread emails and returns the current historyId.
     */
    public List<Message> fetchNewEmails(String accessToken, String userId, String lastHistoryId) throws Exception {
        Gmail service = getGmailService(accessToken);
        List<Message> messages = new ArrayList<>();
        
        if (lastHistoryId != null && !lastHistoryId.isEmpty()) {
            // Use History API for incremental fetching
            try {
                java.math.BigInteger startHistoryId = new java.math.BigInteger(lastHistoryId);
                com.google.api.services.gmail.model.ListHistoryResponse historyResponse = 
                    service.users().history().list(userId)
                        .setStartHistoryId(startHistoryId)
                        .setMaxResults(50L)
                        .execute();
                
                if (historyResponse.getHistory() != null) {
                    for (com.google.api.services.gmail.model.History history : historyResponse.getHistory()) {
                        // Get messages added in this history entry
                        if (history.getMessagesAdded() != null) {
                            for (com.google.api.services.gmail.model.HistoryMessageAdded messageAdded : history.getMessagesAdded()) {
                                Message message = service.users().messages().get(userId, messageAdded.getMessage().getId())
                                    .setFormat("full")
                                    .execute();
                                messages.add(message);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // If historyId is invalid or expired, fall back to regular fetch
                log.warn("History API failed for user {}, falling back to regular fetch: {}", userId, e.getMessage());
                return fetchNewEmailsFallback(service, userId);
            }
        } else {
            // First time or no historyId - use regular fetch
            return fetchNewEmailsFallback(service, userId);
        }
        
        return messages;
    }

    /**
     * Fallback method to fetch unread emails when History API cannot be used.
     */
    private List<Message> fetchNewEmailsFallback(Gmail service, String userId) throws Exception {
        // Query for unread emails that are not archived
        String query = "is:unread -in:archive";
        ListMessagesResponse response = service.users().messages().list(userId)
            .setQ(query)
            .setMaxResults(50L)
            .execute();
        
        List<Message> messages = new ArrayList<>();
        if (response.getMessages() != null) {
            for (com.google.api.services.gmail.model.Message messageRef : response.getMessages()) {
                Message message = service.users().messages().get(userId, messageRef.getId())
                    .setFormat("full")
                    .execute();
                messages.add(message);
            }
        }
        return messages;
    }

    /**
     * Get the current historyId for a user's mailbox.
     * This can be stored and used for subsequent History API calls.
     */
    public String getCurrentHistoryId(String accessToken, String userId) throws Exception {
        Gmail service = getGmailService(accessToken);
        com.google.api.services.gmail.model.Profile profile = service.users().getProfile(userId).execute();
        java.math.BigInteger historyId = profile.getHistoryId();
        return historyId != null ? historyId.toString() : null;
    }

    public String extractEmailContent(Message message) throws Exception {
        StringBuilder content = new StringBuilder();
        
        if (message.getPayload() != null) {
            String subject = "";
            String from = "";
            String date = "";
            String body = "";
            
            // Extract headers
            if (message.getPayload().getHeaders() != null) {
                for (com.google.api.services.gmail.model.MessagePartHeader header : message.getPayload().getHeaders()) {
                    String name = header.getName().toLowerCase();
                    String value = header.getValue();
                    switch (name) {
                        case "subject":
                            subject = value;
                            break;
                        case "from":
                            from = value;
                            break;
                        case "date":
                            date = value;
                            break;
                    }
                }
            }
            
            // Extract body - prioritize HTML over plain text
            BodyExtractionResult bodyResult = extractBodyFromParts(message.getPayload());
            body = bodyResult.htmlContent != null && !bodyResult.htmlContent.isEmpty() 
                ? bodyResult.htmlContent 
                : (bodyResult.plainTextContent != null ? bodyResult.plainTextContent : "");
            
            // If we have HTML, include headers as HTML; otherwise as plain text
            if (bodyResult.htmlContent != null && !bodyResult.htmlContent.isEmpty()) {
                // Format headers as HTML
                content.append("<div style='font-family: Arial, sans-serif; padding: 10px; border-bottom: 1px solid #ddd; margin-bottom: 10px;'>");
                content.append("<strong>Subject:</strong> ").append(escapeHtml(subject)).append("<br>");
                content.append("<strong>From:</strong> ").append(escapeHtml(from)).append("<br>");
                content.append("<strong>Date:</strong> ").append(escapeHtml(date));
                content.append("</div>");
                content.append("<div style='font-family: Arial, sans-serif;'>");
                content.append(body);
                content.append("</div>");
            } else {
                // Plain text format
                content.append("Subject: ").append(subject).append("\n");
                content.append("From: ").append(from).append("\n");
                content.append("Date: ").append(date).append("\n\n");
                content.append(body);
            }
        }
        
        return content.toString();
    }

    /**
     * Helper class to store both HTML and plain text content from email parts
     */
    private static class BodyExtractionResult {
        String htmlContent = null;
        String plainTextContent = null;
    }
    
    private BodyExtractionResult extractBodyFromParts(MessagePart part) {
        BodyExtractionResult result = new BodyExtractionResult();
        
        if (part.getBody() != null && part.getBody().getData() != null) {
            String data = part.getBody().getData();
            String mimeType = part.getMimeType();
            
            if (mimeType != null && (mimeType.equals("text/plain") || mimeType.equals("text/html"))) {
                try {
                    // Gmail uses URL-safe Base64 encoding - decode directly without replacement
                    // Base64.getUrlDecoder() handles '-' and '_' characters correctly
                    byte[] decodedBytes = Base64.getUrlDecoder().decode(data);
                    String decodedText = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
                    
                    // Store HTML and plain text separately
                    if (mimeType.equals("text/html")) {
                        result.htmlContent = decodedText;
                    } else if (mimeType.equals("text/plain")) {
                        result.plainTextContent = decodedText;
                    }
                } catch (IllegalArgumentException e) {
                    // If URL-safe decoding fails (e.g., contains invalid characters), try padding and standard Base64
                    try {
                        // Add padding if needed
                        String paddedData = data;
                        int remainder = paddedData.length() % 4;
                        if (remainder > 0) {
                            paddedData += "=".repeat(4 - remainder);
                        }
                        // Try standard Base64 decoder
                        byte[] decodedBytes = Base64.getDecoder().decode(paddedData);
                        String decodedText = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
                        
                        // Store HTML and plain text separately
                        if (mimeType.equals("text/html")) {
                            result.htmlContent = decodedText;
                        } else if (mimeType.equals("text/plain")) {
                            result.plainTextContent = decodedText;
                        }
                    } catch (Exception e2) {
                        // If both fail, log and skip this part
                        log.warn("Error decoding email body part (mimeType: {}): {}", mimeType, e2.getMessage());
                        // Continue processing other parts
                    }
                } catch (Exception e) {
                    log.error("Unexpected error decoding email body: {}", e.getMessage(), e);
                }
            }
        }
        
        // Recursively process sub-parts and merge results (HTML takes priority)
        if (part.getParts() != null) {
            for (MessagePart subPart : part.getParts()) {
                BodyExtractionResult subResult = extractBodyFromParts(subPart);
                // Merge results - HTML takes priority, but accumulate plain text
                if (subResult.htmlContent != null && !subResult.htmlContent.isEmpty()) {
                    result.htmlContent = (result.htmlContent != null ? result.htmlContent + "\n" : "") + subResult.htmlContent;
                }
                if (subResult.plainTextContent != null && !subResult.plainTextContent.isEmpty()) {
                    result.plainTextContent = (result.plainTextContent != null ? result.plainTextContent + "\n" : "") + subResult.plainTextContent;
                }
            }
        }
        
        return result;
    }
    
    /**
     * Escape HTML special characters for safe display
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    public void archiveEmail(String accessToken, String userId, String messageId) throws Exception {
        Gmail service = getGmailService(accessToken);
        
        // To archive an email in Gmail, remove the INBOX label
        // This moves it out of the inbox without deleting it
        ModifyMessageRequest mods = new ModifyMessageRequest()
            .setAddLabelIds(Collections.emptyList())
            .setRemoveLabelIds(Collections.singletonList("INBOX"));
        
        service.users().messages().modify(userId, messageId, mods).execute();
    }

    public void deleteEmail(String accessToken, String userId, String messageId) throws Exception {
        Gmail service = getGmailService(accessToken);
        service.users().messages().trash(userId, messageId).execute();
    }

    public Message getEmail(String accessToken, String userId, String messageId) throws Exception {
        Gmail service = getGmailService(accessToken);
        return service.users().messages().get(userId, messageId)
            .setFormat("full")
            .execute();
    }
}
