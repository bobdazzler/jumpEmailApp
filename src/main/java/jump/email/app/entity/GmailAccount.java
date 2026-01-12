package jump.email.app.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "gmail_accounts")
@Getter
@Setter
@ToString(exclude = "user")
@EqualsAndHashCode(exclude = "user")
public class GmailAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private String emailAddress;

    private boolean primaryAccount;

    @Embedded
    private OAuthToken token;

    private String lastHistoryId;

    @Enumerated(EnumType.STRING)
    private SyncStatus syncStatus;
}
