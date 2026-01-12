package jump.email.app.config;

import com.theokanning.openai.service.OpenAiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAiConfig {

    @Bean
    public OpenAiService openAiService(
            @Value("${openai.api.key}") String apiKey
    ) {
        return new OpenAiService(apiKey);
    }
}

