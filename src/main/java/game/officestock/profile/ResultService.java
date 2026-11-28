package game.officestock.profile;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 판 결과 저장과 솔로 명예의 전당.
 * 로그인 없이: 혼자 하기 판은 누가 했든 이름 없이 남기고(상위 몇 %인지 알려 주는 데 쓴다),
 * 퇴근 화면에서 올리기를 고른 판만 이달의 명예의 전당에 이름#꼬리표로 오른다. 꼬리표는 기기 열쇠에서 나와
 * 같은 기기면 늘 같고, 다른 기기가 같은 이름을 써도 구분된다.
 */
@Service
public class ResultService {
    /** 명예의 전당을 따로 두는 근무 시간(분). 솔로는 이 길이로만 할 수 있다. */
    public static final List<Integer> BOARD_MINUTES = List.of(5, 10, 15, 20);
    private static final int BOARD_SIZE = 10;

    /** 정산된 한 사람. @param rank 그 판에서의 순위 */
    public record Entry(UUID profileId, long netWorthWon, boolean bankrupt, int rank) {
        public Entry(UUID profileId, long netWorthWon, boolean bankrupt) {
            this(profileId, netWorthWon, bankrupt, 1);
        }
    }

    /** 명예의 전당은 한 달 단위(한국 시간)로 새로 시작한다. */
    static final ZoneId BOARD_ZONE = ZoneId.of("Asia/Seoul");
    /** 기기 열쇠: 화면이 처음 만들어 보관하는 무작위 문자열. */
    private static final Pattern DEVICE_KEY = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    /**
     * 이번 판이 이달 같은 근무 시간 판들 중 어디쯤인지.
     * @param rank 몇 등(더 높은 판 수 + 1) @param topPercent 상위 몇 %(1~100)
     */
    public record Standing(long runId, long rank, int topPercent, long runs) {}

    private final GameResultRepository results;
    private final SoloRunRepository soloRuns;

    ResultService(GameResultRepository results, SoloRunRepository soloRuns) {
        this.results = results;
        this.soloRuns = soloRuns;
    }

    /** 이번 달 1일 0시(한국 시간). */
    static Instant monthStart(Instant now) {
        return ZonedDateTime.ofInstant(now, BOARD_ZONE).withDayOfMonth(1).toLocalDate().atStartOfDay(BOARD_ZONE).toInstant();
    }

    static String month(Instant now) {
        var at = ZonedDateTime.ofInstant(now, BOARD_ZONE);
        return "%d-%02d".formatted(at.getYear(), at.getMonthValue());
    }

    /** 혼자 하기 한 판을 이름 없이 남기고, 이달 같은 길이 판들 중 순위를 돌려준다. */
    @Transactional
    public Standing recordSoloRun(long sessionSeconds, long startingCashWon, long netWorthWon, boolean bankrupt) {
        var now = Instant.now();
        var run = soloRuns.save(new SoloRun((int) sessionSeconds, startingCashWon, netWorthWon, bankrupt, now));
        var from = monthStart(now);
        long runs = soloRuns.countBySessionSecondsAndFinishedAtGreaterThanEqual(run.getSessionSeconds(), from);
        long better = soloRuns.countBySessionSecondsAndFinishedAtGreaterThanEqualAndReturnPercentGreaterThan(
                run.getSessionSeconds(), from, run.getReturnPercent());
        int top = (int) Math.max(1, Math.min(100, Math.ceil((better + 1) * 100.0 / Math.max(1, runs))));
        return new Standing(run.getId(), better + 1, top, runs);
    }

    /** 올린 결과: 화면에 보여 줄 이름#꼬리표와 이달 명예의 전당에서의 자리(10위 밖이면 null). */
    public record Shared(String name, String tag, Integer rank) {}

    /**
     * 퇴근 화면에서 고른 판을 명예의 전당에 올린다. 한 판은 한 번만 올릴 수 있다.
     * @param deviceKey 화면이 보관하는 기기 열쇠(원문은 저장하지 않는다)
     */
    @Transactional
    public Shared share(long runId, String deviceKey, String name) {
        if (deviceKey == null || !DEVICE_KEY.matcher(deviceKey).matches())
            throw new IllegalArgumentException("기기 정보를 확인할 수 없습니다. 새로고침한 뒤 다시 해 보세요.");
        var run = soloRuns.findById(runId).orElseThrow(() -> new IllegalStateException("올릴 판을 찾을 수 없습니다."));
        if (run.isShared()) throw new IllegalStateException("이미 명예의 전당에 올린 판입니다.");
        var hash = sha256(deviceKey);
        var tag = tagOf(hash);
        run.share(name, tag, hash, Instant.now());
        soloRuns.flush();
        Integer rank = null;
        int position = 0;
        for (var row : soloRuns.board(run.getSessionSeconds(), monthStart(run.getFinishedAt()), BOARD_SIZE)) {
            position++;
            if (row.getName().equals(name) && row.getTag().equals(tag)) { rank = position; break; }
        }
        return new Shared(name, tag, rank);
    }

    /** 기기 열쇠 해시에서 네 자리 숫자 꼬리표. 같은 기기면 늘 같다. */
    static String tagOf(String hash) {
        return "%04d".formatted(Long.parseLong(hash.substring(0, 8), 16) % 10_000);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    /** @param playerCount 그 판에 함께한 사람 수(이름 없는 사람 포함) */
    public void record(String mode, long sessionSeconds, long startingCashWon, String roomCode, int playerCount,
                       List<Entry> entries) {
        var now = Instant.now();
        for (var e : entries) {
            results.save(new GameResult(e.profileId(), mode, (int) sessionSeconds, startingCashWon, e.netWorthWon(),
                    e.bankrupt(), playerCount, roomCode, now, e.rank()));
        }
    }

    /** 이달의 명예의 전당: 근무 시간별 상위 10명(올린 판만). 최고 수익률 순이고, 올린 판 수와 평균을 함께 준다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> leaderboard() {
        var from = monthStart(Instant.now());
        var boards = new ArrayList<Map<String, Object>>();
        for (int minutes : BOARD_MINUTES) {
            var entries = new ArrayList<Map<String, Object>>();
            int rank = 0;
            for (var row : soloRuns.board(minutes * 60, from, BOARD_SIZE)) {
                entries.add(Map.of("rank", ++rank, "name", row.getName(), "tag", row.getTag(),
                        "bestReturnPercent", round(row.getBest()), "games", row.getGames(),
                        "averageReturnPercent", round(row.getAverage())));
            }
            boards.add(Map.of("minutes", minutes, "entries", entries));
        }
        return boards;
    }

    /** 지금 보여 주는 명예의 전당의 달("2026-11"). */
    public String boardMonth() {
        return month(Instant.now());
    }

    /** 내 전적: 요약과 최근 20판. 회원 탈퇴하면 함께 지워진다. */
    @Transactional(readOnly = true)
    public Map<String, Object> records(UUID profileId) {
        var s = results.summary(profileId);
        var summary = new java.util.LinkedHashMap<String, Object>();
        summary.put("games", s.getGames());
        summary.put("soloGames", s.getSoloGames());
        summary.put("multiGames", s.getMultiGames());
        summary.put("multiWins", s.getMultiWins());
        summary.put("bestReturnPercent", s.getBest() == null ? null : round(s.getBest()));
        summary.put("averageReturnPercent", s.getAverage() == null ? null : round(s.getAverage()));
        summary.put("totalProfitWon", s.getTotalProfit() == null ? 0 : s.getTotalProfit());
        summary.put("bankrupt", s.getBankrupt());
        var recent = new ArrayList<Map<String, Object>>();
        for (var r : results.findTop20ByProfileIdOrderByFinishedAtDesc(profileId)) {
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("mode", r.getMode());
            row.put("minutes", Math.round(r.getSessionSeconds() / 60.0));
            row.put("playerCount", r.getPlayerCount());
            row.put("rank", r.getRank());
            row.put("profitWon", r.getNetWorthWon() - r.getStartingCashWon());
            row.put("returnPercent", round(r.getReturnPercent()));
            row.put("bankrupt", r.isBankrupt());
            row.put("finishedAt", r.getFinishedAt().toString());
            recent.add(row);
        }
        return Map.of("summary", summary, "recent", recent);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
