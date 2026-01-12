package jump.email.app.repository;

import jump.email.app.entity.GmailAccount;
import jump.email.app.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface GmailAccountRepository extends JpaRepository<GmailAccount, String> {
    List<GmailAccount> findByUser(User user);
    Optional<GmailAccount> findByEmailAddress(String emailAddress);
    Optional<GmailAccount> findByUserAndEmailAddress(User user, String emailAddress);
    Optional<GmailAccount> findByUserAndPrimaryAccountTrue(User user);
}
