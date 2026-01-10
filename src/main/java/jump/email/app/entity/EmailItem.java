package jump.email.app.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class EmailItem {
    @Id
    @GeneratedValue
    private Long id;
    private String gmailId;
    private String summary;
    private String originalContent;
    private Long categoryId;
    private String userId;
}
