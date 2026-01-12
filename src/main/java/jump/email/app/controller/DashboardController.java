package jump.email.app.controller;

import jump.email.app.entity.GmailAccount;
import jump.email.app.entity.User;
import jump.email.app.repository.CategoryRepository;
import jump.email.app.repository.EmailItemRepository;
import jump.email.app.repository.GmailAccountRepository;
import jump.email.app.service.OAuthTokenService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class DashboardController {
    private final OAuthTokenService oauthTokenService;
    private final EmailItemRepository emailItemRepository;
    private final CategoryRepository categoryRepository;
    private final GmailAccountRepository gmailAccountRepository;

    public DashboardController(
            OAuthTokenService oauthTokenService,
            EmailItemRepository emailItemRepository,
            CategoryRepository categoryRepository,
            GmailAccountRepository gmailAccountRepository) {
        this.oauthTokenService = oauthTokenService;
        this.emailItemRepository = emailItemRepository;
        this.categoryRepository = categoryRepository;
        this.gmailAccountRepository = gmailAccountRepository;
    }

    @GetMapping("/home")
    public String home(Authentication authentication, Model model) {
        User user = oauthTokenService.getOrCreateUser(authentication);
        List<GmailAccount> accounts = gmailAccountRepository.findByUser(user);
        
        // Get all categories from all accounts
        List<jump.email.app.entity.EmailCategory> allCategories = new ArrayList<>();
        int totalSorted = 0;
        for (GmailAccount account : accounts) {
            allCategories.addAll(categoryRepository.findByGmailAccount(account));
            totalSorted += emailItemRepository.findByGmailAccount(account).size();
        }
        
        model.addAttribute("userName", user.getPrimaryEmail());
        model.addAttribute("userEmail", user.getPrimaryEmail());
        model.addAttribute("totalSorted", totalSorted);
        model.addAttribute("connectedAccounts", accounts);
        model.addAttribute("categories", allCategories);
        
        return "dashboard";
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/home";
    }
}
