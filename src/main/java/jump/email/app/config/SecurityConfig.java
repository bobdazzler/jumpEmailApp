package jump.email.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    private final OAuth2SuccessHandler oauth2SuccessHandler;
    private final ClientRegistrationRepository clientRegistrationRepository;

    public SecurityConfig(OAuth2SuccessHandler oauth2SuccessHandler, ClientRegistrationRepository clientRegistrationRepository) {
        this.oauth2SuccessHandler = oauth2SuccessHandler;
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Who can see what
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/login", "/error", "/css/**", "/js/**", "/images/**").permitAll()
                        .requestMatchers("/api/webhooks/**").authenticated() // Webhook endpoints require auth
                        .anyRequest().authenticated()
                )

                // OAuth2 Login (Google) configuration
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .successHandler(oauth2SuccessHandler)
                        .failureUrl("/login?error=true")
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestResolver(customAuthorizationRequestResolver())
                        )
                )

                // Logout configuration - allow GET requests for logout links
                .logout(logout -> logout
                        .logoutUrl("/logout")  // Explicitly set logout URL
                        .logoutSuccessUrl("/login?logout=true")  // Redirect to login after logout
                        .invalidateHttpSession(true)  // Invalidate session
                        .clearAuthentication(true)  // Clear authentication
                        .permitAll()
                        // Note: Spring Security requires POST by default, but we have a LogoutController for GET requests
                );

        return http.build();
    }

    @Bean
    public OAuth2AuthorizationRequestResolver customAuthorizationRequestResolver() {
        // Get the default resolver
        DefaultOAuth2AuthorizationRequestResolver defaultResolver = 
            new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, "/oauth2/authorization");
        
        // Return our custom resolver that adds access_type=offline
        return new CustomOAuth2AuthorizationRequestResolver(defaultResolver);
    }
}
