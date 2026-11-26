package game.officestock.profile;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 판 결과 저장과 솔로 명예의 전당. */
@Service
public class ResultService {
    /** 명예의 전당을 따로 두는 근무 시간(분). 솔로는 이 길이로만 할 수 있다. */
    public static final List<Integer> BOARD_MINUTES = List.of(5, 10, 15, 20);
    private static final int BOARD_SIZE = 10;

    /** 정산된 한 사람. */
    public record Entry(UUID profileId, long netWorthWon, boolean bankrupt) {}

    private final GameResultRepository results;

    ResultService(GameResultRepository results) {
        this.results = results;
    }

    @Transactional
    /** @param playerCount 그 판에 함께한 사람 수(이름 없는 사람 포함) */
    public void record(String mode, long sessionSeconds, long startingCashWon, String roomCode, int playerCount,
                       List<Entry> entries) {
        var now = Instant.now();
        for (var e : entries) {
            results.save(new GameResult(e.profileId(), mode, (int) sessionSeconds, startingCashWon, e.netWorthWon(),
                    e.bankrupt(), playerCount, roomCode, now));
        }
    }

    /** 근무 시간별 상위 10명. 최고 수익률 순이고, 판 수와 평균 수익률을 함께 준다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> leaderboard() {
        var boards = new ArrayList<Map<String, Object>>();
        for (int minutes : BOARD_MINUTES) {
            var entries = new ArrayList<Map<String, Object>>();
            int rank = 0;
            for (var row : results.board(minutes * 60, BOARD_SIZE)) {
                entries.add(Map.of("rank", ++rank, "name", row.getName(),
                        "bestReturnPercent", round(row.getBest()), "games", row.getGames(),
                        "averageReturnPercent", round(row.getAverage())));
            }
            boards.add(Map.of("minutes", minutes, "entries", entries));
        }
        return boards;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
