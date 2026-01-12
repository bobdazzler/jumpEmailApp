package jump.email.app.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jump.email.app.entity.User;
import jump.email.app.service.OAuthTokenService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.stream.Collectors;

@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final OAuthTokenService oauthTokenService;

    public OAuth2SuccessHandler(
            OAuth2AuthorizedClientService authorizedClientService,
            OAuthTokenService oauthTokenService) {
        this.authorizedClientService = authorizedClientService;
        this.oauthTokenService = oauthTokenService;
        setDefaultTargetUrl("/home");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        if (authentication instanceof OAuth2AuthenticationToken) {
            OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
            
            // Get the authorized client to access the token
            OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                oauthToken.getAuthorizedClientRegistrationId(),
                oauthToken.getName()
            );
            
            if (client != null) {
                OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
                String email = oauth2User.getAttribute("email");
                
                // Get or create user
                User user = oauthTokenService.getOrCreateUser(authentication);
                
                // Store the access token and refresh token in GmailAccount
                String scopes = client.getAccessToken().getScopes() != null 
                    ? client.getAccessToken().getScopes().stream().collect(Collectors.joining(","))
                    : "";
                String refreshToken = client.getRefreshToken() != null 
                    ? client.getRefreshToken().getTokenValue() 
                    : null;
                oauthTokenService.storeAccessToken(user, client.getAccessToken(), refreshToken, email, scopes);
            }
        }
        
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
