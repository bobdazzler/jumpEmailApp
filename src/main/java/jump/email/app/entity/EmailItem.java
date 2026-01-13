package jump.email.app.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "email_items")
@Data
public class EmailItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    private String gmailId;
    
    @Column(columnDefinition = "TEXT")
    private String summary;
    
    @Column(columnDefinition = "TEXT")
    private String originalContent;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private EmailCategory category;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gmail_account_id")
    private GmailAccount gmailAccount;
    
    // Unsubscribe tracking
    private Boolean unsubscribed = false;
    private String unsubscribeLink;
    private String unsubscribeStatus; // "PENDING", "SUCCESS", "FAILED", "NOT_FOUND"
}
