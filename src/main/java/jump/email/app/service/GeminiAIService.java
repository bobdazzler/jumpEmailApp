package jump.email.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jump.email.app.entity.EmailCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini AI Service implementation.
 * Much cheaper than OpenAI: ~$0.25/1M input tokens, $0.50/1M output tokens
 * Free tier: 15 requests/minute
 */
@Slf4j
public class GeminiAIService implements EmailClassificationService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent";

    public GeminiAIService(@Value("${gemini.api.key:}") String apiKey) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        
        if (apiKey == null || apiKey.isEmpty() || apiKey.startsWith("${")) {
            log.warn("Gemini API key not configured. Set gemini.api.key in application.properties or environment variable.");
        }
    }

    @Override
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
            
            String response = callGeminiAPI(prompt, 50, 0.3f);
            return response.trim();
        } catch (Exception e) {
            handleError(e, "email classification");
            return null;
        }
    }

    @Override
    public String summarizeEmail(String emailContent) {
        try {
            String prompt = String.format(
                "Summarize the following email in 2-3 sentences. Focus on the main point and any action items:\n\n%s",
                emailContent.length() > 3000 ? emailContent.substring(0, 3000) + "..." : emailContent
            );
            
            return callGeminiAPI(prompt, 150, 0.5f);
        } catch (Exception e) {
            handleError(e, "email summarization");
            return null;
        }
    }

    @Override
    public String extractUnsubscribeLink(String emailContent) {
        try {
            String prompt = String.format(
                "Extract the unsubscribe URL from this email. Look for links that contain 'unsubscribe', 'opt-out', or similar terms. " +
                "Return ONLY the URL, nothing else. If no unsubscribe link is found, return 'NOT_FOUND'.\n\nEmail:\n%s",
                emailContent.length() > 2000 ? emailContent.substring(0, 2000) + "..." : emailContent
            );
            
            String result = callGeminiAPI(prompt, 200, 0.1f);
            result = result.replaceAll("[\"'`]", "").trim();
            if (result.startsWith("http")) {
                return result;
            }
            return null;
        } catch (Exception e) {
            handleError(e, "unsubscribe link extraction");
            return null;
        }
    }

    @Override
    public String generateUnsubscribeInstructions(String htmlContent) {
        try {
            String prompt = String.format(
                "Analyze this unsubscribe page HTML and provide step-by-step instructions to unsubscribe. " +
                "Identify: 1) Form fields that need to be filled, 2) Checkboxes/selects to toggle, 3) Button to click. " +
                "Format as JSON: {\"actions\": [{\"type\": \"fill\", \"field\": \"email\", \"value\": \"user@example.com\"}, {\"type\": \"click\", \"selector\": \"#unsubscribe-btn\"}]}\n\nHTML:\n%s",
                htmlContent.length() > 5000 ? htmlContent.substring(0, 5000) + "..." : htmlContent
            );
            
            return callGeminiAPI(prompt, 500, 0.2f);
        } catch (Exception e) {
            handleError(e, "unsubscribe instructions generation");
            return null;
        }
    }

    private String callGeminiAPI(String prompt, int maxTokens, float temperature) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.startsWith("${")) {
            throw new QuotaException("Gemini API key not configured", new RuntimeException("Missing API key"));
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> requestBody = new HashMap<>();
            Map<String, Object> contents = new HashMap<>();
            Map<String, Object> part = new HashMap<>();
            part.put("text", prompt);
            contents.put("parts", List.of(part));
            requestBody.put("contents", List.of(contents));
            
            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("maxOutputTokens", maxTokens);
            generationConfig.put("temperature", temperature);
            requestBody.put("generationConfig", generationConfig);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            String url = GEMINI_API_URL + "?key=" + apiKey;
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                
                if (jsonResponse.has("candidates") && jsonResponse.get("candidates").isArray() 
                    && jsonResponse.get("candidates").size() > 0) {
                    JsonNode candidate = jsonResponse.get("candidates").get(0);
                    if (candidate.has("content") && candidate.get("content").has("parts")) {
                        JsonNode parts = candidate.get("content").get("parts");
                        if (parts.isArray() && parts.size() > 0) {
                            return parts.get(0).get("text").asText();
                        }
                    }
                }
                
                throw new RuntimeException("Unexpected Gemini API response format: " + response.getBody());
            } else {
                throw new RuntimeException("Gemini API error: " + response.getStatusCode() + " - " + response.getBody());
            }
        } catch (Exception e) {
            if (e instanceof QuotaException) {
                throw new RuntimeException( e);
            }
            handleError(e, "Gemini API call");
            return null;
        }
    }

    private void handleError(Exception e, String operation) {
        String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        
        // Check for quota/rate limit errors (429, 403 with quota message)
        if (errorMessage.contains("quota") || 
            errorMessage.contains("exceeded") ||
            errorMessage.contains("rate limit") ||
            errorMessage.contains("429") ||
            errorMessage.contains("resource exhausted")) {
            throw new QuotaException("Gemini quota/rate limit exceeded during " + operation + ": " + e.getMessage(), e);
        }
        
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        throw new RuntimeException("Gemini API error during " + operation + ": " + e.getMessage(), e);
    }
}
