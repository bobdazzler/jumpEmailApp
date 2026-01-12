package jump.email.app.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;

import java.time.Instant;

@Embeddable
@Data
public class OAuthToken {
    @Column(length = 4000)
    private String accessToken;

    @Column(length = 4000)
    private String refreshToken;

    private Instant expiry;

    private String scopes;
}
