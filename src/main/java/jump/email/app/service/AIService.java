package jump.email.app.service;

import com.google.api.client.util.Value;
import com.theokanning.openai.completion.CompletionRequest;
import com.theokanning.openai.service.OpenAiService;
import jump.email.app.entity.Category;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AIService {
    private final OpenAiService openAiService;

    public AIService(@Value("${openai.api.key}") String apiKey) {
        openAiService = new OpenAiService(apiKey);
    }

    public String classifyEmail(String emailContent, List<Category> categories) {
         Prompt: "Classify this email into one of: [list categories with desc]. Output only category name."
        CompletionRequest request = CompletionRequest.builder().model("gpt-4").prompt(prompt).build();
        return openAiService.createCompletion(request).getChoices().get(0).getText();
    }

    public String summarizeEmail(String emailContent) {
        // Similar prompt for summary
        return "";
    }
}
