//for now leaving this testing out

//package jump.email.app.service;
//
//import com.theokanning.openai.OpenAiHttpException;
//import com.theokanning.openai.service.OpenAiService;
//import jump.email.app.entity.EmailCategory;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.MockedConstruction;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.test.util.ReflectionTestUtils;
//
//import java.util.List;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//class AIServiceTest {
//
//    @Mock
//    private OpenAiService openAiService;
//
//    @InjectMocks
//    private AIService aiService;
//
//    private List<EmailCategory> testCategories;
//
//    @BeforeEach
//    void setUp() {
//        EmailCategory category1 = new EmailCategory();
//        category1.setName("Work");
//        category1.setDescription("Work-related emails");
//
//        EmailCategory category2 = new EmailCategory();
//        category2.setName("Personal");
//        category2.setDescription("Personal emails");
//
//        testCategories = List.of(category1, category2);
//    }
//
//    @Test
//    void classifyEmail_WithValidInput_ShouldReturnCategoryName() {
//        // given
//        String emailContent = "Subject: Meeting\n\nLet's schedule a meeting for tomorrow.";
//
//        // when
//        String result = aiService.classifyEmail(emailContent, testCategories);
//
//        // then
//        assertNotNull(result);
//        // (later you can assert exact category once OpenAI response is mocked)
//    }
//
//    @Test
//    void extractUnsubscribeLink_WithValidEmail_ShouldReturnLink() {
//        String emailBody = """
//            Click here to unsubscribe:
//            https://example.com/unsubscribe
//        """;
//
//        String link = aiService.extractUnsubscribeLink(emailBody);
//
//        assertEquals("https://example.com/unsubscribe", link);
//    }
//
//    @Test
//    void generateUnsubscribeInstructions_ShouldNotThrow() {
//        String html = "<a href='https://example.com/unsubscribe'>Unsubscribe</a>";
//
//        assertDoesNotThrow(() ->
//                aiService.generateUnsubscribeInstructions(html)
//        );
//    }
//}
//
