package jump.email.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Who can see what
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/login", "/error", "/css/**", "/js/**", "/images/**").permitAll()
                        .anyRequest().authenticated()
                )

                // OAuth2 Login (Google) configuration
                .oauth2Login(oauth2 -> oauth2
                                .loginPage("/login")                    // your custom login page (optional)
                                .defaultSuccessUrl("/home", true)       // where to go after successful login
                        // .failureUrl("/login?error=true")     // optional
                        // .permitAll()                         // usually not needed
                )

                // Nice defaults for logout (optional but recommended)
                .logout(logout -> logout
                        .logoutSuccessUrl("/")
                        .permitAll()
                );

        return http.build();
    }
}
