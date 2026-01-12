package jump.email.app.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "email_categories")
@Data
public class EmailCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gmail_account_id")
    private GmailAccount gmailAccount;

    private String name;
    @Column(columnDefinition = "TEXT")
    private String description;
}