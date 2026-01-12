package jump.email.app.repository;

import jump.email.app.entity.EmailCategory;
import jump.email.app.entity.GmailAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface CategoryRepository extends JpaRepository<EmailCategory, String> {
    List<EmailCategory> findByGmailAccount(GmailAccount gmailAccount);
    Optional<EmailCategory> findByGmailAccountAndNameIgnoreCase(GmailAccount gmailAccount, String name);
}
