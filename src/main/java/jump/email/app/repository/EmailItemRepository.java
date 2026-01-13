package jump.email.app.repository;

import jump.email.app.entity.EmailCategory;
import jump.email.app.entity.EmailItem;
import jump.email.app.entity.GmailAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;


@Repository
public interface EmailItemRepository extends JpaRepository<EmailItem, String> {
    List<EmailItem> findByGmailAccount(GmailAccount gmailAccount);
    List<EmailItem> findByCategory(EmailCategory category);
    EmailItem findByGmailIdAndGmailAccount(String gmailId, GmailAccount gmailAccount);
    
    // Fetch with relationships to avoid LazyInitializationException
    @Query("SELECT e FROM EmailItem e JOIN FETCH e.gmailAccount g JOIN FETCH g.user LEFT JOIN FETCH e.category WHERE e.id = :id")
    Optional<EmailItem> findByIdWithAccountAndUser(@Param("id") String id);
}
