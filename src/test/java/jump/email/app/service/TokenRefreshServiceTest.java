package jump.email.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jump.email.app.entity.GmailAccount;
import jump.email.app.entity.OAuthToken;
import jump.email.app.entity.SyncStatus;
import jump.email.app.entity.User;
import jump.email.app.repository.GmailAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenRefreshServiceTest {

    @Mock
    private GmailAccountRepository gmailAccountRepository;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private TokenRefreshService tokenRefreshService;

    private GmailAccount testAccount;
    private OAuthToken testToken;
    private User testUser;

    @BeforeEach
    void setUp() {
        // Create service instance (it creates RestTemplate in constructor, but we'll override it)
        tokenRefreshService = new TokenRefreshService(gmailAccountRepository);
        
        // Setup test user
        testUser = new User();
        testUser.setId("user123");
        testUser.setPrimaryEmail("test@example.com");

        // Setup test token
        testToken = new OAuthToken();
        testToken.setAccessToken("old_access_token");
        testToken.setRefreshToken("refresh_token_123");
        testToken.setExpiry(Instant.now().minusSeconds(3600)); // Expired
        testToken.setScopes("openid profile email");

        // Setup test account
        testAccount = new GmailAccount();
        testAccount.setId("account123");
        testAccount.setUser(testUser);
        testAccount.setEmailAddress("test@example.com");
        testAccount.setPrimaryAccount(true);
        testAccount.setSyncStatus(SyncStatus.ACTIVE);
        testAccount.setToken(testToken);

        // Inject mocks using reflection to override constructor-created dependencies
        ReflectionTestUtils.setField(tokenRefreshService, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(tokenRefreshService, "clientId", "test_client_id");
        ReflectionTestUtils.setField(tokenRefreshService, "clientSecret", "test_client_secret");
    }

    @Test
    void ensureValidAccessToken_WithValidToken_ShouldReturnSameToken() {
        // Given
        testToken.setExpiry(Instant.now().plusSeconds(3600)); // Not expired

        // When
        String result = tokenRefreshService.ensureValidAccessToken(testAccount);

        // Then
        assertEquals("old_access_token", result);
        verify(restTemplate, never()).postForEntity(anyString(), any(), any(Class.class));
        verify(gmailAccountRepository, never()).save(any(GmailAccount.class));
    }

    @Test
    void ensureValidAccessToken_WithExpiredToken_ShouldRefreshToken() {
        // Given
        testToken.setExpiry(Instant.now().minusSeconds(3600)); // Expired
        String refreshResponse = "{\"access_token\":\"new_access_token\",\"expires_in\":3600}";
        ResponseEntity<String> response = new ResponseEntity<>(refreshResponse, HttpStatus.OK);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(response);
        when(gmailAccountRepository.findById("account123"))
                .thenReturn(Optional.of(testAccount));

        // When
        String result = tokenRefreshService.ensureValidAccessToken(testAccount);

        // Then
        verify(restTemplate).postForEntity(anyString(), any(), eq(String.class));
        ArgumentCaptor<GmailAccount> accountCaptor = ArgumentCaptor.forClass(GmailAccount.class);
        verify(gmailAccountRepository, atLeastOnce()).save(accountCaptor.capture());
        GmailAccount savedAccount = accountCaptor.getValue();
        assertEquals("new_access_token", savedAccount.getToken().getAccessToken());
        assertEquals(SyncStatus.ACTIVE, savedAccount.getSyncStatus());
    }

    @Test
    void ensureValidAccessToken_WithNoRefreshToken_ShouldThrowException() {
        // Given
        testToken.setRefreshToken(null);
        testToken.setExpiry(Instant.now().minusSeconds(3600)); // Expired

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            tokenRefreshService.ensureValidAccessToken(testAccount);
        });

        assertTrue(exception.getMessage().contains("no refresh token"));
        verify(gmailAccountRepository).save(argThat(account ->
            account.getSyncStatus() == SyncStatus.EXPIRED
        ));
    }

    @Test
    void ensureValidAccessToken_WithRefreshFailure_ShouldMarkAsError() {
        // Given
        testToken.setExpiry(Instant.now().minusSeconds(3600)); // Expired
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RestClientException("Token refresh failed"));

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            tokenRefreshService.ensureValidAccessToken(testAccount);
        });

        assertTrue(exception.getMessage().contains("Failed to refresh"));
        verify(gmailAccountRepository).save(argThat(account ->
            account.getSyncStatus() == SyncStatus.ERROR
        ));
    }

    @Test
    void refreshTokenOn401_ShouldRefreshAndReturnNewToken() {
        // Given
        String refreshResponse = "{\"access_token\":\"new_access_token\",\"expires_in\":3600}";
        ResponseEntity<String> response = new ResponseEntity<>(refreshResponse, HttpStatus.OK);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(response);
        when(gmailAccountRepository.findById("account123"))
                .thenReturn(Optional.of(testAccount));

        // When
        String result = tokenRefreshService.refreshTokenOn401(testAccount);

        // Then
        assertEquals("new_access_token", result);
        verify(restTemplate).postForEntity(anyString(), any(), eq(String.class));
        verify(gmailAccountRepository).save(argThat(account ->
            account.getToken().getAccessToken().equals("new_access_token")
        ));
    }

    @Test
    void refreshTokenOn401_WithNoRefreshToken_ShouldThrowException() {
        // Given
        testToken.setRefreshToken(null);

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            tokenRefreshService.refreshTokenOn401(testAccount);
        });

        assertTrue(exception.getMessage().contains("no refresh token"));
        verify(gmailAccountRepository).save(argThat(account ->
            account.getSyncStatus() == SyncStatus.EXPIRED
        ));
    }
}
