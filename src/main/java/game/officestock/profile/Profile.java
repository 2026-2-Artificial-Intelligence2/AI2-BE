package game.officestock.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** 플레이어 프로필. 로그인 대신 브라우저에 둔 비밀 열쇠로 찾는다. */
@Entity
@Table(name = "profile")
public class Profile {
    @Id
    private UUID id;
    @Column(nullable = false, length = 10)
    private String name;
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;
    /** 꾸미기 설정(JSON 객체 문자열). 내용은 프론트엔드가 정한다. */
    @Column(nullable = false)
    private String customization;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Profile() {}

    Profile(String name, String tokenHash, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.tokenHash = tokenHash;
        this.customization = "{}";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getCustomization() { return customization; }

    void rename(String name, Instant now) {
        this.name = name;
        this.updatedAt = now;
    }

    void customize(String customization, Instant now) {
        this.customization = customization;
        this.updatedAt = now;
    }
}
