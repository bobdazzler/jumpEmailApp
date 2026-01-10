package jump.email.app.repository;

import jump.email.app.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    Category findByUserId(String userId);
}
