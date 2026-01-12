package jump.email.app.service;

import com.theokanning.openai.OpenAiHttpException;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import jump.email.app.entity.EmailCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class AIService implements EmailClassificationService {
    private final OpenAiService openAiService;

    public AIService(OpenAiService openAiService) {
        this.openAiService = openAiService;
    }
    
    /**
     * Custom exception for OpenAI quota/rate limit errors
     * @deprecated Use EmailClassificationService.QuotaException instead
     */
    @Deprecated
    public static class OpenAIQuotaException extends EmailClassificationService.QuotaException {
        public OpenAIQuotaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    private void handleOpenAIError(Exception e, String operation) {
        String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        
        // Check for quota exceeded or rate limit errors
        if (e instanceof OpenAiHttpException || 
            errorMessage.contains("quota") || 
            errorMessage.contains("exceeded") ||
            errorMessage.contains("rate limit") ||
            (e.getCause() != null && e.getCause().getMessage() != null && 
             e.getCause().getMessage().toLowerCase().contains("429"))) {
            throw new QuotaException("OpenAI quota/rate limit exceeded during " + operation + ": " + e.getMessage(), e);
        }
        
        // Re-throw other exceptions as-is
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        throw new RuntimeException("OpenAI API error during " + operation + ": " + e.getMessage(), e);
    }

    public String classifyEmail(String emailContent, List<EmailCategory> categories) {
        try {
            StringBuilder categoriesList = new StringBuilder();
            for (EmailCategory cat : categories) {
                categoriesList.append(String.format("- %s: %s\n", cat.getName(), cat.getDescription()));
            }
            
            String prompt = String.format(
                "Classify the following email into one of these categories based on their descriptions:\n\n%s\n\n" +
                "Email content:\n%s\n\n" +
                "Respond with ONLY the category name, nothing else.",
                categoriesList.toString(),
                emailContent.length() > 2000 ? emailContent.substring(0, 2000) + "..." : emailContent
            );
            
            ChatMessage message = new ChatMessage("user", prompt);
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("gpt-3.5-turbo")
                .messages(List.of(message))
                .maxTokens(50)
                .temperature(0.3)
                .build();
            
            return openAiService.createChatCompletion(request)
                .getChoices().get(0).getMessage().getContent().trim();
        } catch (Exception e) {
            handleOpenAIError(e, "email classification");
            return null; // Never reached, but needed for compilation
        }
    }

    public String summarizeEmail(String emailContent) {
        try {
            String prompt = String.format(
                "Summarize the following email in 2-3 sentences. Focus on the main point and any action items:\n\n%s",
                emailContent.length() > 3000 ? emailContent.substring(0, 3000) + "..." : emailContent
            );
            
            ChatMessage message = new ChatMessage("user", prompt);
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("gpt-3.5-turbo")
                .messages(List.of(message))
                .maxTokens(150)
                .temperature(0.5)
                .build();
            
            return openAiService.createChatCompletion(request)
                .getChoices().get(0).getMessage().getContent().trim();
        } catch (Exception e) {
            handleOpenAIError(e, "email summarization");
            return null; // Never reached, but needed for compilation
        }
    }

    public String extractUnsubscribeLink(String emailContent) {
        try {
            // Use AI to find unsubscribe link in email HTML/text
            String prompt = String.format(
                "Extract the unsubscribe URL from this email. Look for links that contain 'unsubscribe', 'opt-out', or similar terms. " +
                "Return ONLY the URL, nothing else. If no unsubscribe link is found, return 'NOT_FOUND'.\n\nEmail:\n%s",
                emailContent.length() > 2000 ? emailContent.substring(0, 2000) + "..." : emailContent
            );
            
            ChatMessage message = new ChatMessage("user", prompt);
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("gpt-3.5-turbo")
                .messages(List.of(message))
                .maxTokens(200)
                .temperature(0.1)
                .build();
            
            String result = openAiService.createChatCompletion(request)
                .getChoices().get(0).getMessage().getContent().trim();
            
            // Clean up the result
            result = result.replaceAll("[\"'`]", "").trim();
            if (result.startsWith("http")) {
                return result;
            }
            return null;
        } catch (Exception e) {
            handleOpenAIError(e, "unsubscribe link extraction");
            return null; // Never reached, but needed for compilation
        }
    }

    public String generateUnsubscribeInstructions(String htmlContent) {
        try {
            // AI to analyze unsubscribe page and generate instructions
            String prompt = String.format(
                "Analyze this unsubscribe page HTML and provide step-by-step instructions to unsubscribe. " +
                "Identify: 1) Form fields that need to be filled, 2) Checkboxes/selects to toggle, 3) Button to click. " +
                "Format as JSON: {\"actions\": [{\"type\": \"fill\", \"field\": \"email\", \"value\": \"user@example.com\"}, {\"type\": \"click\", \"selector\": \"#unsubscribe-btn\"}]}\n\nHTML:\n%s",
                htmlContent.length() > 5000 ? htmlContent.substring(0, 5000) + "..." : htmlContent
            );
            
            ChatMessage message = new ChatMessage("user", prompt);
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("gpt-3.5-turbo")
                .messages(List.of(message))
                .maxTokens(500)
                .temperature(0.2)
                .build();
            
            return openAiService.createChatCompletion(request)
                .getChoices().get(0).getMessage().getContent().trim();
        } catch (Exception e) {
            handleOpenAIError(e, "unsubscribe instructions generation");
            return null; // Never reached, but needed for compilation
        }
    }
}
