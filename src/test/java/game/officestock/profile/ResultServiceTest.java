package game.officestock.profile;

import game.officestock.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 판 결과 저장과 솔로 명예의 전당. */
@SpringBootTest
@Import(TestDatabase.class)
class ResultServiceTest {
    @Autowired
    ProfileService profiles;
    @Autowired
    ResultService results;

    @Autowired
    SoloRunRepository soloRuns;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> board(int minutes) {
        return (List<Map<String, Object>>) results.leaderboard().stream().filter(b -> b.get("minutes").equals(minutes))
                .findFirst().orElseThrow().get("entries");
    }

    private static String key(String seed) {
        return (seed + "-device-key-0123456789").substring(0, 24);
    }

    @Test
    void 혼자_하기는_올린_판만_이달의_명예의_전당에_오르고_기기_꼬리표로_구분한다() {
        String me = "꾸준" + System.nanoTime() % 10_000, lucky = "한방" + System.nanoTime() % 10_000;
        long start = 10_000_000;
        // 7분 판은 다른 테스트와 섞이지 않는다(명예의 전당에는 5·10·15·20분만 있지만 기록은 남는다).
        var a = results.recordSoloRun(300, start, 11_000_000, false);   // +10%
        var b = results.recordSoloRun(300, start, 11_200_000, false);   // +12%
        var c = results.recordSoloRun(300, start, 13_000_000, false);   // +30%
        results.recordSoloRun(300, start, 19_000_000, false);           // +90%, 올리지 않음
        var mine1 = results.share(a.runId(), key("me"), me);
        var mine2 = results.share(b.runId(), key("me"), me);
        var other = results.share(c.runId(), key("other"), me);       // 다른 기기가 같은 이름
        assertThat(mine1.tag()).isEqualTo(mine2.tag()).matches("\\d{4}");
        assertThat(other.tag()).isNotEqualTo(mine1.tag());

        var rows = board(5).stream().filter(e -> e.get("name").equals(me)).toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("tag")).isEqualTo(other.tag());
        assertThat(rows.get(0).get("bestReturnPercent")).isEqualTo(30.0);
        assertThat(rows.get(1).get("tag")).isEqualTo(mine1.tag());
        assertThat(rows.get(1).get("bestReturnPercent")).isEqualTo(12.0);
        assertThat(rows.get(1).get("games")).isEqualTo(2L);
        assertThat(rows.get(1).get("averageReturnPercent")).isEqualTo(11.0);
        assertThat(board(5)).noneMatch(e -> e.get("bestReturnPercent").equals(90.0) && e.get("name").equals(lucky));
        assertThat(results.leaderboard()).extracting(m -> m.get("minutes")).containsExactly(5, 10, 15, 20);

        assertThatThrownBy(() -> results.share(a.runId(), key("me"), me)).hasMessageContaining("이미");
        assertThatThrownBy(() -> results.share(results.recordSoloRun(300, start, start, false).runId(), "짧다", me))
                .hasMessageContaining("기기");
    }

    @Test
    void 상위_몇_퍼센트는_이달_같은_길이_판들_가운데_순위다() {
        int seconds = 7 * 60 + 1;   // 다른 테스트와 겹치지 않는 길이
        long start = 10_000_000;
        var low = results.recordSoloRun(seconds, start, 9_000_000, false);
        assertThat(low.topPercent()).isEqualTo(100);
        assertThat(low.runs()).isEqualTo(1);
        results.recordSoloRun(seconds, start, 10_000_000, false);
        results.recordSoloRun(seconds, start, 10_500_000, false);
        var best = results.recordSoloRun(seconds, start, 12_000_000, false);
        assertThat(best.runs()).isEqualTo(4);
        assertThat(best.topPercent()).isEqualTo(25);   // 4판 중 1등
        assertThat(best.rank()).isEqualTo(1);
        var second = results.recordSoloRun(seconds, start, 11_000_000, false);
        assertThat(second.topPercent()).isEqualTo(40);   // 5판 중 2등
        assertThat(second.rank()).isEqualTo(2);
    }

    @Test
    void 명예의_전당은_한국_시간_달마다_새로_시작한다() {
        // 11월 30일 23시(한국) = 30일 14시(UTC) → 11월, 12월 1일 0시 30분(한국) = 11월 30일 15시 30분(UTC) → 12월
        assertThat(ResultService.month(java.time.Instant.parse("2026-11-30T14:00:00Z"))).isEqualTo("2026-11");
        assertThat(ResultService.month(java.time.Instant.parse("2026-11-30T15:30:00Z"))).isEqualTo("2026-12");
        assertThat(ResultService.monthStart(java.time.Instant.parse("2026-11-30T15:30:00Z")))
                .isEqualTo(java.time.Instant.parse("2026-11-30T15:00:00Z"));

        // 지난달에 올린 판은 이달 순위에 없다.
        String name = "지난달" + System.nanoTime() % 1000;
        var old = soloRuns.save(new SoloRun(600, 10_000_000, 20_000_000, false,
                ResultService.monthStart(java.time.Instant.now()).minusSeconds(3600)));
        results.share(old.getId(), key("old"), name);
        assertThat(board(10)).noneMatch(e -> e.get("name").equals(name));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 내_전적은_요약과_최근_판을_주고_탈퇴하면_함께_지워진다() {
        var me = profiles.signup("전적이" + System.nanoTime() % 1000, "secret1").profile();
        long start = 10_000_000;
        results.record("solo", 600, start, "222221", 1, List.of(new ResultService.Entry(me.id(), 11_000_000, false, 1)));
        results.record("multi", 300, start, "222222", 3, List.of(new ResultService.Entry(me.id(), 9_000_000, false, 1)));
        results.record("multi", 300, start, "222223", 3, List.of(new ResultService.Entry(me.id(), 8_000_000, false, 3)));

        var records = results.records(me.id());
        var summary = (Map<String, Object>) records.get("summary");
        assertThat(summary.get("games")).isEqualTo(3L);
        assertThat(summary.get("soloGames")).isEqualTo(1L);
        assertThat(summary.get("multiGames")).isEqualTo(2L);
        assertThat(summary.get("multiWins")).isEqualTo(1L);
        assertThat(summary.get("bestReturnPercent")).isEqualTo(10.0);
        assertThat(summary.get("totalProfitWon")).isEqualTo(-2_000_000L);
        var recent = (List<Map<String, Object>>) records.get("recent");
        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).get("rank")).isEqualTo(3);           // 최근 것부터
        assertThat(recent.get(0).get("profitWon")).isEqualTo(-2_000_000L);
        assertThat(recent.get(2).get("mode")).isEqualTo("solo");

        profiles.delete(me.id(), "secret1");
        assertThat(((Map<String, Object>) results.records(me.id()).get("summary")).get("games")).isEqualTo(0L);
    }
}
