package jump.email.app.service;

import jump.email.app.entity.GmailAccount;
import jump.email.app.entity.OAuthToken;
import jump.email.app.entity.SyncStatus;
import jump.email.app.entity.User;
import jump.email.app.repository.GmailAccountRepository;
import jump.email.app.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class OAuthTokenService {
    private final UserRepository userRepository;
    private final GmailAccountRepository gmailAccountRepository;

    public OAuthTokenService(UserRepository userRepository, GmailAccountRepository gmailAccountRepository) {
        this.userRepository = userRepository;
        this.gmailAccountRepository = gmailAccountRepository;
    }

    @Transactional
    public User getOrCreateUser(Authentication authentication) {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        String userId = oauth2User.getName(); // Google user ID
        String email = oauth2User.getAttribute("email");
        
        Optional<User> existingUser = userRepository.findById(userId);
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            // Update primary email if changed
            if (email != null && !email.equals(user.getPrimaryEmail())) {
                user.setPrimaryEmail(email);
                userRepository.save(user);
            }
            return user;
        } else {
            User user = new User();
            user.setId(userId);
            user.setPrimaryEmail(email);
            user.setGmailAccounts(new ArrayList<>());
            return userRepository.save(user);
        }
    }

    @Transactional
    public GmailAccount storeAccessToken(User user, OAuth2AccessToken accessToken, String refreshToken, String email, String scopes) {
        // Check if GmailAccount already exists for this email
        Optional<GmailAccount> existingAccount = gmailAccountRepository.findByUserAndEmailAddress(user, email);
        
        GmailAccount gmailAccount;
        if (existingAccount.isPresent()) {
            gmailAccount = existingAccount.get();
        } else {
            gmailAccount = new GmailAccount();
            gmailAccount.setUser(user);
            gmailAccount.setEmailAddress(email);
            
            // If this is the first account, make it primary
            if (user.getGmailAccounts().isEmpty()) {
                gmailAccount.setPrimaryAccount(true);
            } else {
                gmailAccount.setPrimaryAccount(false);
            }
            
            user.getGmailAccounts().add(gmailAccount);
        }
        
        // Update OAuth token
        OAuthToken token = new OAuthToken();
        token.setAccessToken(accessToken.getTokenValue());
        token.setRefreshToken(refreshToken);
        token.setExpiry(accessToken.getExpiresAt());
        token.setScopes(scopes);
        
        gmailAccount.setToken(token);
        gmailAccount.setSyncStatus(SyncStatus.ACTIVE);
        
        return gmailAccountRepository.save(gmailAccount);
    }

    public GmailAccount getPrimaryGmailAccount(User user) {
        Optional<GmailAccount> primary = gmailAccountRepository.findByUserAndPrimaryAccountTrue(user);
        if (primary.isPresent()) {
            return primary.get();
        }
        // Fallback to first account if no primary set
        List<GmailAccount> accounts = gmailAccountRepository.findByUser(user);
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    public List<GmailAccount> getAllGmailAccounts(User user) {
        return gmailAccountRepository.findByUser(user);
    }
}
