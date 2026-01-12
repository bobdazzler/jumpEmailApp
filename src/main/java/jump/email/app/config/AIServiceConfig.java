package jump.email.app.config;

import com.theokanning.openai.service.OpenAiService;
import jump.email.app.service.AIService;
import jump.email.app.service.EmailClassificationService;
import jump.email.app.service.GeminiAIService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuration to switch between AI providers.
 * Set ai.provider=gemini or ai.provider=openai in application.properties
 */
@Configuration
public class AIServiceConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "ai.provider", havingValue = "gemini", matchIfMissing = false)
    public EmailClassificationService geminiAIService(@Value("${gemini.api.key:}") String apiKey) {
        return new GeminiAIService(apiKey);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "ai.provider", havingValue = "openai", matchIfMissing = true)
    public EmailClassificationService openAIService(@Value("${openai.api.key:}") String apiKey) {
        OpenAiService openAiService = new OpenAiService(apiKey);
        return new AIService(openAiService);
    }
}
