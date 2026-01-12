package jump.email.app.repository;

import jump.email.app.entity.EmailCategory;
import jump.email.app.entity.EmailItem;
import jump.email.app.entity.GmailAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailItemRepository extends JpaRepository<EmailItem, String> {
    List<EmailItem> findByGmailAccount(GmailAccount gmailAccount);
    List<EmailItem> findByCategory(EmailCategory category);
    EmailItem findByGmailIdAndGmailAccount(String gmailId, GmailAccount gmailAccount);
}
