package jump.email.app.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Entity
@Data
public class User {
    @Id
    private String id; // From OAuth
    private String email;
    @OneToMany
    private List<String> accessTokens = new ArrayList<>(); // For multiple accounts
}
