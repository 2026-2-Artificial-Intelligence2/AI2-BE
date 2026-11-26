package game.officestock.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** 기기(브라우저) 하나의 자동 로그인 열쇠. 원문은 브라우저에만 있고 여기에는 SHA-256 해시만 둔다. */
@Entity
@Table(name = "login_token")
public class LoginToken {
    @Id
    @Column(name = "token_hash", length = 64)
    private String tokenHash;
    @Column(name = "profile_id", nullable = false)
    private UUID profileId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    protected LoginToken() {}

    LoginToken(String tokenHash, UUID profileId, Instant now) {
        this.tokenHash = tokenHash;
        this.profileId = profileId;
        this.createdAt = now;
        this.lastUsedAt = now;
    }

    UUID getProfileId() { return profileId; }

    void touch(Instant now) { this.lastUsedAt = now; }
}
