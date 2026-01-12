package jump.email.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@ToString(exclude = "gmailAccounts")
@EqualsAndHashCode(exclude = "gmailAccounts")
public class User {
    @Id
    private String id; // OAuth subject / internal UUID

    private String primaryEmail;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GmailAccount> gmailAccounts = new ArrayList<>();
}
