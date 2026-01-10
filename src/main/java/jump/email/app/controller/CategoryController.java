package jump.email.app.controller;

import jump.email.app.entity.Category;
import jump.email.app.repository.CategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

@Controller
public class CategoryController {
    @Autowired
    private CategoryRepository repo;

    @GetMapping("/categories")
    public String listCategories(Model model, Principal principal) {
        model.addAttribute("categories", repo.findByUserId(principal.getName()));
        return "categories";
    }

    @PostMapping("/categories/add")
    public String addCategory(@RequestParam String name, @RequestParam String description, Principal principal) {
        Category cat = new Category();
        cat.setName(name);
        cat.setDescription(description);
        cat.setUserId(principal.getName());
        repo.save(cat);
        return "redirect:/categories"; // Or HTMX partial
    }
}
