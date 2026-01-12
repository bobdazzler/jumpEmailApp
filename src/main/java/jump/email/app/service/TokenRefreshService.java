package jump.email.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jump.email.app.entity.GmailAccount;
import jump.email.app.entity.OAuthToken;
import jump.email.app.entity.SyncStatus;
import jump.email.app.repository.GmailAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

@Slf4j
@Service
public class TokenRefreshService {
    private final GmailAccountRepository gmailAccountRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;
    
    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String clientSecret;

    public TokenRefreshService(GmailAccountRepository gmailAccountRepository) {
        this.gmailAccountRepository = gmailAccountRepository;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }
    
    private void validateClientCredentials() {
        if (clientId == null || clientId.isEmpty() || clientId.startsWith("${")) {
            throw new RuntimeException("Google OAuth client-id is not configured. Please set spring.security.oauth2.client.registration.google.client-id");
        }
        if (clientSecret == null || clientSecret.isEmpty() || clientSecret.startsWith("${")) {
            throw new RuntimeException("Google OAuth client-secret is not configured. Please set spring.security.oauth2.client.registration.google.client-secret");
        }
    }

    /**
     * Refreshes the access token for a Gmail account if it's expired or about to expire.
     * Returns the valid access token (either existing or newly refreshed).
     * Reloads the account from database after refresh to ensure we have the latest token.
     */
    @Transactional
    public String ensureValidAccessToken(GmailAccount account) {
        if (account.getToken() == null || account.getToken().getAccessToken() == null) {
            throw new RuntimeException("No access token available for account: " + account.getEmailAddress());
        }

        OAuthToken token = account.getToken();
        Instant now = Instant.now();
        
        // Check if token is expired or expires within the next 5 minutes
        // If expiry is null, treat as expired (shouldn't happen but be safe)
        boolean needsRefresh = token.getExpiry() == null || token.getExpiry().isBefore(now.plusSeconds(300));
        
        if (needsRefresh) {
            // Token is expired or about to expire, refresh it
            if (token.getRefreshToken() == null || token.getRefreshToken().isEmpty()) {
                account.setSyncStatus(SyncStatus.EXPIRED);
                gmailAccountRepository.save(account);
                throw new RuntimeException("Access token expired and no refresh token available for account: " + account.getEmailAddress() + ". Please re-authenticate.");
            }
            
            try {
                log.info("Refreshing access token for account: {}", account.getEmailAddress());
                refreshAccessToken(account);
                // Reload account from database to get the refreshed token
                account = gmailAccountRepository.findById(account.getId())
                    .orElseThrow(() -> new RuntimeException("Account not found after refresh"));
                log.info("Successfully refreshed access token for account: {}", account.getEmailAddress());
            } catch (Exception e) {
                account.setSyncStatus(SyncStatus.ERROR);
                gmailAccountRepository.save(account);
                log.error("Failed to refresh access token for account {}: {}", account.getEmailAddress(), e.getMessage(), e);
                throw new RuntimeException("Failed to refresh access token for account: " + account.getEmailAddress() + ". Error: " + e.getMessage(), e);
            }
        }
        
        return account.getToken().getAccessToken();
    }
    
    /**
     * Handles 401 Unauthorized errors by refreshing the token and retrying.
     * This should be called when a Gmail API call returns 401.
     */
    @Transactional
    public String refreshTokenOn401(GmailAccount account) {
        if (account.getToken() == null || account.getToken().getAccessToken() == null) {
            throw new RuntimeException("No access token available for account: " + account.getEmailAddress());
        }
        
        OAuthToken token = account.getToken();
        if (token.getRefreshToken() == null || token.getRefreshToken().isEmpty()) {
            account.setSyncStatus(SyncStatus.EXPIRED);
            gmailAccountRepository.save(account);
            throw new RuntimeException("Received 401 Unauthorized and no refresh token available for account: " + account.getEmailAddress() + ". Please re-authenticate.");
        }
        
        try {
            log.info("Received 401, refreshing access token for account: {}", account.getEmailAddress());
            refreshAccessToken(account);
            // Reload account from database to get the refreshed token
            account = gmailAccountRepository.findById(account.getId())
                .orElseThrow(() -> new RuntimeException("Account not found after refresh"));
            log.info("Successfully refreshed access token after 401 for account: {}", account.getEmailAddress());
            return account.getToken().getAccessToken();
        } catch (Exception e) {
            account.setSyncStatus(SyncStatus.ERROR);
            gmailAccountRepository.save(account);
            log.error("Failed to refresh access token after 401 for account {}: {}", account.getEmailAddress(), e.getMessage(), e);
            throw new RuntimeException("Failed to refresh access token after 401 for account: " + account.getEmailAddress(), e);
        }
    }

    /**
     * Refreshes the access token using the refresh token.
     */
    @Transactional
    public void refreshAccessToken(GmailAccount account) {
        validateClientCredentials();
        
        OAuthToken token = account.getToken();
        
        if (token.getRefreshToken() == null || token.getRefreshToken().isEmpty()) {
            throw new RuntimeException("No refresh token available for account: " + account.getEmailAddress() + ". Please re-authenticate by signing in again.");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", clientId);
            body.add("client_secret", clientSecret);
            body.add("refresh_token", token.getRefreshToken());
            body.add("grant_type", "refresh_token");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                "https://oauth2.googleapis.com/token",
                request,
                String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                
                if (!jsonResponse.has("access_token")) {
                    throw new RuntimeException("Token refresh response missing access_token. Response: " + response.getBody());
                }
                
                String newAccessToken = jsonResponse.get("access_token").asText();
                long expiresInSeconds = jsonResponse.has("expires_in") 
                    ? jsonResponse.get("expires_in").asLong() 
                    : 3600; // Default to 1 hour if not provided
                Instant expiresIn = Instant.now().plusSeconds(expiresInSeconds);
                
                // Update the token
                token.setAccessToken(newAccessToken);
                token.setExpiry(expiresIn);
                // Refresh token might be updated, but usually stays the same
                if (jsonResponse.has("refresh_token") && !jsonResponse.get("refresh_token").isNull()) {
                    token.setRefreshToken(jsonResponse.get("refresh_token").asText());
                }
                
                account.setToken(token);
                account.setSyncStatus(SyncStatus.ACTIVE);
                gmailAccountRepository.save(account);
                log.info("Token refreshed successfully for account: {}, expires at: {}", account.getEmailAddress(), expiresIn);
            } else {
                String errorBody = response.getBody() != null ? response.getBody() : "No response body";
                throw new RuntimeException("Failed to refresh token. Status: " + response.getStatusCode() + ", Body: " + errorBody);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error refreshing access token: " + e.getMessage(), e);
        }
    }
}
