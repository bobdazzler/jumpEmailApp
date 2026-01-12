package jump.email.app.controller;

import jump.email.app.entity.EmailItem;
import jump.email.app.entity.User;
import jump.email.app.repository.EmailItemRepository;
import jump.email.app.service.OAuthTokenService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class EmailController {
    private final EmailItemRepository emailItemRepository;
    private final OAuthTokenService oauthTokenService;

    public EmailController(
            EmailItemRepository emailItemRepository,
            OAuthTokenService oauthTokenService) {
        this.emailItemRepository = emailItemRepository;
        this.oauthTokenService = oauthTokenService;
    }

    @GetMapping("/email/{id}")
    @Transactional(readOnly = true)
    public String viewEmail(@PathVariable String id, Authentication authentication, Model model) {
        User user = oauthTokenService.getOrCreateUser(authentication);
        
        EmailItem emailItem = emailItemRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Email not found"));
        
        if (!emailItem.getGmailAccount().getUser().getId().equals(user.getId())) {
            return "redirect:/home";
        }
        
        // Optionally fetch full email from Gmail if needed
        String fullContent = emailItem.getOriginalContent();
        
        model.addAttribute("email", emailItem);
        model.addAttribute("fullContent", fullContent);
        model.addAttribute("userName", user.getPrimaryEmail());
        
        return "email-view";
    }
}
