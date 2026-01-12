package jump.email.app.repository;

import jump.email.app.entity.EmailCategory;
import jump.email.app.entity.EmailItem;
import jump.email.app.entity.GmailAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface EmailItemRepository extends JpaRepository<EmailItem, String> {
    List<EmailItem> findByGmailAccount(GmailAccount gmailAccount);
    List<EmailItem> findByCategory(EmailCategory category);
    EmailItem findByGmailIdAndGmailAccount(String gmailId, GmailAccount gmailAccount);
}
