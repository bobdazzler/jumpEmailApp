package jump.email.app.controller;

import jump.email.app.entity.EmailCategory;
import jump.email.app.entity.EmailItem;
import jump.email.app.entity.GmailAccount;
import jump.email.app.entity.User;
import jump.email.app.repository.CategoryRepository;
import jump.email.app.repository.EmailItemRepository;
import jump.email.app.repository.GmailAccountRepository;
import jump.email.app.service.OAuthTokenService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
public class CategoryController {
    private final CategoryRepository categoryRepository;
    private final EmailItemRepository emailItemRepository;
    private final OAuthTokenService oauthTokenService;
    private final GmailAccountRepository gmailAccountRepository;

    public CategoryController(
            CategoryRepository categoryRepository,
            EmailItemRepository emailItemRepository,
            OAuthTokenService oauthTokenService,
            GmailAccountRepository gmailAccountRepository) {
        this.categoryRepository = categoryRepository;
        this.emailItemRepository = emailItemRepository;
        this.oauthTokenService = oauthTokenService;
        this.gmailAccountRepository = gmailAccountRepository;
    }

    @GetMapping("/categories/add-form")
    public String addCategoryForm() {
        return "fragments :: category-form";
    }

    @PostMapping("/categories/add")
    public String addCategory(
            @RequestParam String name, 
            @RequestParam String description,
            @RequestParam(required = false) String gmailAccountId,
            Authentication authentication) {
        User user = oauthTokenService.getOrCreateUser(authentication);
        
        // Get primary account if not specified
        GmailAccount account;
        if (gmailAccountId != null && !gmailAccountId.isEmpty()) {
            account = gmailAccountRepository.findById(gmailAccountId)
                .orElseThrow(() -> new RuntimeException("Gmail account not found"));
            // Verify account belongs to user
            if (!account.getUser().getId().equals(user.getId())) {
                return "redirect:/home";
            }
        } else {
            account = oauthTokenService.getPrimaryGmailAccount(user);
            if (account == null) {
                return "redirect:/home?error=no_account";
            }
        }
        
        EmailCategory cat = new EmailCategory();
        cat.setName(name);
        cat.setDescription(description);
        cat.setGmailAccount(account);
        categoryRepository.save(cat);
        
        return "redirect:/home";
    }

    @GetMapping("/category/{id}")
    @Transactional(readOnly = true)
    public String viewCategory(@PathVariable String id, Authentication authentication, Model model) {
        User user = oauthTokenService.getOrCreateUser(authentication);
        
        EmailCategory category = categoryRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Category not found"));
        
        // Verify category belongs to user's account
        if (!category.getGmailAccount().getUser().getId().equals(user.getId())) {
            return "redirect:/home";
        }
        
        List<EmailItem> emails = emailItemRepository.findByCategory(category);
        
        model.addAttribute("category", category);
        model.addAttribute("emails", emails);
        model.addAttribute("userName", user.getPrimaryEmail());
        
        return "category-view";
    }
}
