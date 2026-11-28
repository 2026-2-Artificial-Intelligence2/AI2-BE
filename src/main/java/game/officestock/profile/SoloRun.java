package game.officestock.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 혼자 하기 한 판. 이름 없이 남고, 명예의 전당에 올리면 이름과 기기 꼬리표가 붙는다. */
@Entity
@Table(name = "solo_run")
public class SoloRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_seconds", nullable = false)
    private int sessionSeconds;
    @Column(name = "starting_cash_won", nullable = false)
    private long startingCashWon;
    @Column(name = "net_worth_won", nullable = false)
    private long netWorthWon;
    @Column(name = "return_percent", nullable = false)
    private double returnPercent;
    @Column(nullable = false)
    private boolean bankrupt;
    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;
    @Column(length = 10)
    private String name;
    @Column(length = 4)
    private String tag;
    @Column(name = "device_hash", length = 64)
    private String deviceHash;
    @Column(name = "shared_at")
    private Instant sharedAt;

    protected SoloRun() {}

    SoloRun(int sessionSeconds, long startingCashWon, long netWorthWon, boolean bankrupt, Instant finishedAt) {
        this.sessionSeconds = sessionSeconds;
        this.startingCashWon = startingCashWon;
        this.netWorthWon = netWorthWon;
        this.returnPercent = startingCashWon == 0 ? 0 : (netWorthWon - startingCashWon) * 100.0 / startingCashWon;
        this.bankrupt = bankrupt;
        this.finishedAt = finishedAt;
    }

    void share(String name, String tag, String deviceHash, Instant at) {
        this.name = name;
        this.tag = tag;
        this.deviceHash = deviceHash;
        this.sharedAt = at;
    }

    public Long getId() { return id; }
    public int getSessionSeconds() { return sessionSeconds; }
    public double getReturnPercent() { return returnPercent; }
    public Instant getFinishedAt() { return finishedAt; }
    public boolean isShared() { return sharedAt != null; }
    public String getName() { return name; }
    public String getTag() { return tag; }
}
