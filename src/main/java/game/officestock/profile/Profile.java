package game.officestock.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** 회원 프로필. 이름 + 비밀번호로 가입·로그인하고, 기기마다 자동 로그인 열쇠(LoginToken)를 둔다. */
@Entity
@Table(name = "profile")
public class Profile {
    @Id
    private UUID id;
    @Column(nullable = false, length = 10)
    private String name;
    /** BCrypt 해시. V1에서 열쇠만으로 만든 프로필은 비어 있을 수 있다. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;
    /** 꾸미기 설정(JSON 객체 문자열). 내용은 프론트엔드가 정한다. */
    @Column(nullable = false)
    private String customization;
    /** 비밀번호 복구 코드의 BCrypt 해시. 아직 만들지 않았으면 비어 있다. */
    @Column(name = "recovery_hash", length = 100)
    private String recoveryHash;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Profile() {}

    Profile(String name, String passwordHash, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.passwordHash = passwordHash;
        this.customization = "{}";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getCustomization() { return customization; }
    String getPasswordHash() { return passwordHash; }
    String getRecoveryHash() { return recoveryHash; }

    void setRecovery(String recoveryHash, Instant now) {
        this.recoveryHash = recoveryHash;
        this.updatedAt = now;
    }

    void changePassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.updatedAt = now;
    }

    void rename(String name, Instant now) {
        this.name = name;
        this.updatedAt = now;
    }

    void customize(String customization, Instant now) {
        this.customization = customization;
        this.updatedAt = now;
    }
}
