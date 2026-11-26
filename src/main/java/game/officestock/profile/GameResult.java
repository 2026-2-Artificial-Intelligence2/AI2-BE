package game.officestock.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** 한 사람의 한 판 결과. 퇴근 정산 때 저장한다. */
@Entity
@Table(name = "game_result")
public class GameResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "profile_id", nullable = false)
    private UUID profileId;
    @Column(nullable = false, length = 8)
    private String mode;
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
    @Column(name = "player_count", nullable = false)
    private int playerCount;
    @Column(name = "room_code", nullable = false, length = 6)
    private String roomCode;
    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    protected GameResult() {}

    GameResult(UUID profileId, String mode, int sessionSeconds, long startingCashWon, long netWorthWon,
               boolean bankrupt, int playerCount, String roomCode, Instant finishedAt) {
        this.profileId = profileId;
        this.mode = mode;
        this.sessionSeconds = sessionSeconds;
        this.startingCashWon = startingCashWon;
        this.netWorthWon = netWorthWon;
        this.returnPercent = startingCashWon == 0 ? 0 : (netWorthWon - startingCashWon) * 100.0 / startingCashWon;
        this.bankrupt = bankrupt;
        this.playerCount = playerCount;
        this.roomCode = roomCode;
        this.finishedAt = finishedAt;
    }
}
