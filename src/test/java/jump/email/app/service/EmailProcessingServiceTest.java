package jump.email.app.service;

import com.google.api.services.gmail.model.Message;
import jump.email.app.entity.EmailCategory;
import jump.email.app.entity.EmailItem;
import jump.email.app.entity.GmailAccount;
import jump.email.app.entity.OAuthToken;
import jump.email.app.entity.SyncStatus;
import jump.email.app.entity.User;
import jump.email.app.repository.CategoryRepository;
import jump.email.app.repository.EmailItemRepository;
import jump.email.app.repository.GmailAccountRepository;
import jump.email.app.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailProcessingServiceTest {

    @Mock
    private GmailApiService gmailApiService;

    @Mock
    private EmailClassificationService emailClassificationService;

    @Mock
    private EmailItemRepository emailItemRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GmailAccountRepository gmailAccountRepository;

    @Mock
    private TokenRefreshService tokenRefreshService;

    @InjectMocks
    private EmailProcessingService emailProcessingService;

    private User testUser;
    private GmailAccount testAccount;
    private EmailCategory testCategory;
    private Message testMessage;

    @BeforeEach
    void setUp() {
        // Setup test user
        testUser = new User();
        testUser.setId("user123");
        testUser.setPrimaryEmail("test@example.com");

        // Setup test account
        testAccount = new GmailAccount();
        testAccount.setId("account123");
        testAccount.setUser(testUser);
        testAccount.setEmailAddress("test@example.com");
        testAccount.setPrimaryAccount(true);
        testAccount.setSyncStatus(SyncStatus.ACTIVE);
        testAccount.setLastHistoryId("history123");

        OAuthToken token = new OAuthToken();
        token.setAccessToken("access_token");
        token.setRefreshToken("refresh_token");
        token.setExpiry(Instant.now().plusSeconds(3600));
        testAccount.setToken(token);

        // Setup test category
        testCategory = new EmailCategory();
        testCategory.setId("category123");
        testCategory.setName("Work");
        testCategory.setDescription("Work-related emails");
        testCategory.setGmailAccount(testAccount);

        // Setup test message
        testMessage = new Message();
        testMessage.setId("message123");

        testUser.setGmailAccounts(new ArrayList<>(List.of(testAccount)));
    }

    @Test
    void processNewEmails_WithNoUsers_ShouldNotProcessAnything() throws Exception {
        // Given
        when(userRepository.findAll()).thenReturn(Collections.emptyList());

        // When
        emailProcessingService.processNewEmails();

        // Then
        verify(gmailApiService, never()).fetchNewEmails(anyString(), anyString(), anyString());
        verify(emailItemRepository, never()).save(any(EmailItem.class));
    }

    @Test
    void processNewEmails_WithAccountWithoutToken_ShouldSkipAccount() throws Exception {
        // Given
        testAccount.setToken(null);
        when(userRepository.findAll()).thenReturn(List.of(testUser));
        when(gmailAccountRepository.findByUser(testUser)).thenReturn(List.of(testAccount));

        // When
        emailProcessingService.processNewEmails();

        // Then
        verify(gmailApiService, never()).fetchNewEmails(anyString(), anyString(), anyString());
        verify(emailItemRepository, never()).save(any(EmailItem.class));
    }

    @Test
    void processNewEmails_WithInactiveAccount_ShouldSkipAccount() {
        // Given
        testAccount.setSyncStatus(SyncStatus.EXPIRED);
        when(userRepository.findAll()).thenReturn(List.of(testUser));
        when(gmailAccountRepository.findByUser(testUser)).thenReturn(List.of(testAccount));

        // When
        emailProcessingService.processNewEmails();

        // Then
        try {
            verify(gmailApiService, never()).fetchNewEmails(anyString(), anyString(), anyString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        verify(emailItemRepository, never()).save(any(EmailItem.class));
    }

    @Test
    void processNewEmails_WithNoCategories_ShouldNotProcessEmails() throws Exception {
        // Given
        when(userRepository.findAll()).thenReturn(List.of(testUser));
        when(gmailAccountRepository.findByUser(testUser)).thenReturn(List.of(testAccount));
        when(tokenRefreshService.ensureValidAccessToken(testAccount)).thenReturn("access_token");
        when(gmailApiService.fetchNewEmails("access_token", "test@example.com", "history123"))
                .thenReturn(List.of(testMessage));
        when(categoryRepository.findByGmailAccount(testAccount)).thenReturn(Collections.emptyList());

        // When
        emailProcessingService.processNewEmails();

        // Then
        verify(gmailApiService).fetchNewEmails("access_token", "test@example.com", "history123");
        verify(emailItemRepository, never()).save(any(EmailItem.class));
        verify(gmailApiService, never()).archiveEmail(anyString(), anyString(), anyString());
    }

    @Test
    void processNewEmails_WithQuotaException_ShouldSaveToUnsortedEmailsCategory() throws Exception {
        // Given
        String emailContent = "Subject: Test\nFrom: sender@example.com\n\nBody content";
        EmailCategory unsortedCategory = new EmailCategory();
        unsortedCategory.setId("unsorted123");
        unsortedCategory.setName("Unsorted Emails");
        unsortedCategory.setGmailAccount(testAccount);

        when(userRepository.findAll()).thenReturn(List.of(testUser));
        when(gmailAccountRepository.findByUser(testUser)).thenReturn(List.of(testAccount));
        when(tokenRefreshService.ensureValidAccessToken(testAccount)).thenReturn("access_token");
        when(gmailApiService.fetchNewEmails("access_token", "test@example.com", "history123"))
                .thenReturn(List.of(testMessage));
        when(categoryRepository.findByGmailAccount(testAccount)).thenReturn(List.of(testCategory));
        when(emailItemRepository.findByGmailIdAndGmailAccount("message123", testAccount)).thenReturn(null);
        when(gmailApiService.extractEmailContent(testMessage)).thenReturn(emailContent);
        when(emailClassificationService.classifyEmail(emailContent, List.of(testCategory)))
                .thenThrow(new EmailClassificationService.QuotaException("Quota exceeded", new RuntimeException()));
        when(categoryRepository.findByGmailAccountAndNameIgnoreCase(testAccount, "Unsorted Emails"))
                .thenReturn(Optional.empty()); // Category doesn't exist, will be created
        
        // Mock save to return the category passed to it (standard JPA repository behavior)
        when(categoryRepository.save(any(EmailCategory.class)))
                .thenAnswer(invocation -> {
                    EmailCategory cat = invocation.getArgument(0);
                    // Set an ID to simulate persistence
                    if (cat.getId() == null) {
                        cat.setId("generated-id");
                    }
                    return cat;
                });

        // When
        emailProcessingService.processNewEmails();

        // Then - Verify Unsorted Emails category was created
        ArgumentCaptor<EmailCategory> categoryCaptor = ArgumentCaptor.forClass(EmailCategory.class);
        verify(categoryRepository).save(categoryCaptor.capture());
        EmailCategory createdCategory = categoryCaptor.getValue();
        assertNotNull(createdCategory, "Category should not be null");
        assertEquals("Unsorted Emails", createdCategory.getName());
        assertEquals(testAccount, createdCategory.getGmailAccount());

        // Then - Verify email was saved with the Unsorted Emails category
        ArgumentCaptor<EmailItem> emailItemCaptor = ArgumentCaptor.forClass(EmailItem.class);
        verify(emailItemRepository).save(emailItemCaptor.capture());
        EmailItem savedEmail = emailItemCaptor.getValue();
        assertNotNull(savedEmail, "Email should not be null");
        assertEquals("message123", savedEmail.getGmailId());
        assertTrue(savedEmail.getSummary().contains("quota limit"));
        assertNotNull(savedEmail.getCategory(), "Email category should not be null");
        assertEquals("Unsorted Emails", savedEmail.getCategory().getName());
        assertEquals(testAccount, savedEmail.getCategory().getGmailAccount());
        verify(gmailApiService).archiveEmail("access_token", "test@example.com", "message123");
    }

//    @Test
//    void processNewEmails_WithUnmatchedCategory_ShouldSaveToUnsortedEmailsCategory() throws Exception {
//        // Given
//        String emailContent = "Subject: Test\nFrom: sender@example.com\n\nBody content";
//        EmailCategory unsortedCategory = new EmailCategory();
//        unsortedCategory.setId("unsorted123");
//        unsortedCategory.setName("Unsorted Emails");
//        unsortedCategory.setGmailAccount(testAccount);
//
//        when(userRepository.findAll()).thenReturn(List.of(testUser));
//        when(gmailAccountRepository.findByUser(testUser)).thenReturn(List.of(testAccount));
//        when(tokenRefreshService.ensureValidAccessToken(testAccount)).thenReturn("access_token");
//        when(gmailApiService.fetchNewEmails("access_token", "test@example.com", "history123"))
//                .thenReturn(List.of(testMessage));
//        when(categoryRepository.findByGmailAccount(testAccount)).thenReturn(List.of(testCategory));
//        when(emailItemRepository.findByGmailIdAndGmailAccount("message123", testAccount)).thenReturn(null);
//        when(gmailApiService.extractEmailContent(testMessage)).thenReturn(emailContent);
//        when(emailClassificationService.classifyEmail(emailContent, List.of(testCategory))).thenReturn("UnknownCategory");
//        when(categoryRepository.findByGmailAccountAndNameIgnoreCase(testAccount, "Unsorted Emails"))
//                .thenReturn(Optional.of(unsortedCategory)); // Category already exists
//
//        // When
//        emailProcessingService.processNewEmails();
//
//        // Then
//        ArgumentCaptor<EmailItem> emailItemCaptor = ArgumentCaptor.forClass(EmailItem.class);
//        verify(emailItemRepository).save(emailItemCaptor.capture());
//        EmailItem savedEmail = emailItemCaptor.getValue();
//        assertEquals("message123", savedEmail.getGmailId());
//        assertEquals(unsortedCategory, savedEmail.getCategory());
//        assertTrue(savedEmail.getSummary().contains("UnknownCategory"));
//        verify(gmailApiService).archiveEmail("access_token", "test@example.com", "message123");
//    }

    @Test
    void processNewEmails_WithNewEmails_ShouldProcessAndSaveEmails() throws Exception {
        // Given
        String emailContent = "Subject: Test\nFrom: sender@example.com\n\nBody content";
        when(userRepository.findAll()).thenReturn(List.of(testUser));
        when(gmailAccountRepository.findByUser(testUser)).thenReturn(List.of(testAccount));
        when(tokenRefreshService.ensureValidAccessToken(testAccount)).thenReturn("access_token");
        when(gmailApiService.fetchNewEmails("access_token", "test@example.com", "history123"))
                .thenReturn(List.of(testMessage));
        when(categoryRepository.findByGmailAccount(testAccount)).thenReturn(List.of(testCategory));
        when(emailItemRepository.findByGmailIdAndGmailAccount("message123", testAccount)).thenReturn(null);
        when(gmailApiService.extractEmailContent(testMessage)).thenReturn(emailContent);
        when(emailClassificationService.classifyEmail(emailContent, List.of(testCategory))).thenReturn("Work");
        when(emailClassificationService.summarizeEmail(emailContent)).thenReturn("Test summary");
        when(gmailApiService.getCurrentHistoryId("access_token", "test@example.com")).thenReturn("history456");

        // When
        emailProcessingService.processNewEmails();

        // Then
        verify(tokenRefreshService).ensureValidAccessToken(testAccount);
        verify(gmailApiService).fetchNewEmails("access_token", "test@example.com", "history123");
        verify(emailClassificationService).classifyEmail(emailContent, List.of(testCategory));
        verify(emailClassificationService).summarizeEmail(emailContent);

        ArgumentCaptor<EmailItem> emailItemCaptor = ArgumentCaptor.forClass(EmailItem.class);
        verify(emailItemRepository).save(emailItemCaptor.capture());
        EmailItem savedEmail = emailItemCaptor.getValue();
        assertEquals("message123", savedEmail.getGmailId());
        assertEquals("Test summary", savedEmail.getSummary());
        assertEquals(emailContent, savedEmail.getOriginalContent());
        assertEquals(testCategory, savedEmail.getCategory());
        assertEquals(testAccount, savedEmail.getGmailAccount());

        verify(gmailApiService).archiveEmail("access_token", "test@example.com", "message123");
        verify(gmailAccountRepository).save(argThat(account -> 
            account.getLastHistoryId().equals("history456")
        ));
    }

    @Test
    void processNewEmails_WithAlreadyProcessedEmail_ShouldSkip() throws Exception {
        // Given
        EmailItem existingEmail = new EmailItem();
        existingEmail.setGmailId("message123");

        when(userRepository.findAll()).thenReturn(List.of(testUser));
        when(gmailAccountRepository.findByUser(testUser))
                .thenReturn(List.of(testAccount));
        when(tokenRefreshService.ensureValidAccessToken(testAccount))
                .thenReturn("access_token");
        when(gmailApiService.fetchNewEmails(any(), any(), any()))
                .thenReturn(List.of(testMessage));
        when(emailItemRepository.findByGmailIdAndGmailAccount("message123", testAccount))
                .thenReturn(existingEmail);

        // When
        emailProcessingService.processNewEmails();

        // Then
        verify(gmailApiService, never()).extractEmailContent(any());
        verify(emailClassificationService, never()).classifyEmail(anyString(), anyList());
        verify(emailItemRepository, never()).save(any());
        verify(gmailApiService, never()).archiveEmail(any(), any(), any());
    }




    @Test
    void processNewEmails_WithUnmatchedCategory_ShouldSaveToUnsortedEmailsCategory() throws Exception {
        // Given
        String emailContent = "Subject: Test\nFrom: sender@example.com\n\nBody content";
        EmailCategory unsortedCategory = new EmailCategory();
        unsortedCategory.setId("unsorted123");
        unsortedCategory.setName("Unsorted Emails");
        unsortedCategory.setGmailAccount(testAccount);

        when(userRepository.findAll()).thenReturn(List.of(testUser));
        when(gmailAccountRepository.findByUser(testUser)).thenReturn(List.of(testAccount));
        when(tokenRefreshService.ensureValidAccessToken(testAccount)).thenReturn("access_token");
        when(gmailApiService.fetchNewEmails("access_token", "test@example.com", "history123"))
                .thenReturn(List.of(testMessage));
        when(categoryRepository.findByGmailAccount(testAccount)).thenReturn(List.of(testCategory));
        when(emailItemRepository.findByGmailIdAndGmailAccount("message123", testAccount)).thenReturn(null);
        when(gmailApiService.extractEmailContent(testMessage)).thenReturn(emailContent);
        when(emailClassificationService.classifyEmail(emailContent, List.of(testCategory))).thenReturn("UnknownCategory");
        when(categoryRepository.findByGmailAccountAndNameIgnoreCase(testAccount, "Unsorted Emails"))
                .thenReturn(Optional.of(unsortedCategory)); // Category already exists

        // When
        emailProcessingService.processNewEmails();

        // Then - Verify email is saved with existing Unsorted Emails category
        ArgumentCaptor<EmailItem> emailItemCaptor = ArgumentCaptor.forClass(EmailItem.class);
        verify(emailItemRepository).save(emailItemCaptor.capture());
        EmailItem savedEmail = emailItemCaptor.getValue();
        assertEquals("message123", savedEmail.getGmailId());
        assertEquals(unsortedCategory, savedEmail.getCategory());
        assertTrue(savedEmail.getSummary().contains("UnknownCategory"));
        verify(gmailApiService).archiveEmail("access_token", "test@example.com", "message123");
    }

    @Test
    void processNewEmails_With401Error_ShouldRefreshTokenAndRetry() throws Exception {
        // Given
        com.google.api.client.googleapis.json.GoogleJsonResponseException authException =
                mock(com.google.api.client.googleapis.json.GoogleJsonResponseException.class);
        when(authException.getStatusCode()).thenReturn(401);

        when(userRepository.findAll()).thenReturn(List.of(testUser));
        when(gmailAccountRepository.findByUser(testUser)).thenReturn(List.of(testAccount));
        when(tokenRefreshService.ensureValidAccessToken(testAccount)).thenReturn("access_token");
        when(gmailApiService.fetchNewEmails("access_token", "test@example.com", "history123"))
                .thenThrow(authException);
        when(gmailAccountRepository.findById("account123")).thenReturn(Optional.of(testAccount));
        when(tokenRefreshService.refreshTokenOn401(testAccount)).thenReturn("new_access_token");
        when(gmailApiService.fetchNewEmails("new_access_token", "test@example.com", "history123"))
                .thenReturn(Collections.emptyList());

        // When
        emailProcessingService.processNewEmails();

        // Then
        verify(tokenRefreshService).refreshTokenOn401(testAccount);
        verify(gmailApiService, times(2)).fetchNewEmails(anyString(), eq("test@example.com"), eq("history123"));
    }
}
