package game.officestock.profile;

import game.officestock.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 판 결과 저장과 솔로 명예의 전당. */
@SpringBootTest
@Import(TestDatabase.class)
class ResultServiceTest {
    @Autowired
    ProfileService profiles;
    @Autowired
    ResultService results;

    @Test
    @SuppressWarnings("unchecked")
    void 명예의_전당은_근무_시간별_솔로_최고_수익률_순이고_멀티는_빠진다() {
        var steady = profiles.signup("꾸준이" + System.nanoTime() % 1000, "secret1").profile();
        var lucky = profiles.signup("한방이" + System.nanoTime() % 1000, "secret1").profile();
        long start = 10_000_000;
        // 5분: 한방이 +30%(1판), 꾸준이 +10%·+12%(2판)
        results.record("solo", 300, start, "111111", 1, List.of(new ResultService.Entry(lucky.id(), 13_000_000, false)));
        results.record("solo", 300, start, "111112", 1, List.of(new ResultService.Entry(steady.id(), 11_000_000, false)));
        results.record("solo", 300, start, "111113", 1, List.of(new ResultService.Entry(steady.id(), 11_200_000, false)));
        // 10분 판과 멀티 판은 5분 순위에 섞이지 않는다.
        results.record("solo", 600, start, "111114", 1, List.of(new ResultService.Entry(steady.id(), 20_000_000, false)));
        results.record("multi", 300, start, "111115", 2, List.of(new ResultService.Entry(steady.id(), 50_000_000, false)));

        var boards = results.leaderboard();
        var five = (List<Map<String, Object>>) boards.stream().filter(b -> b.get("minutes").equals(5)).findFirst()
                .orElseThrow().get("entries");
        var mine = five.stream().filter(e -> e.get("name").equals(lucky.name()) || e.get("name").equals(steady.name()))
                .toList();
        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).get("name")).isEqualTo(lucky.name());
        assertThat(mine.get(0).get("bestReturnPercent")).isEqualTo(30.0);
        assertThat(mine.get(1).get("bestReturnPercent")).isEqualTo(12.0);
        assertThat(mine.get(1).get("games")).isEqualTo(2L);
        assertThat(mine.get(1).get("averageReturnPercent")).isEqualTo(11.0);
        assertThat(boards).extracting(b -> b.get("minutes")).containsExactly(5, 10, 15, 20);
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
