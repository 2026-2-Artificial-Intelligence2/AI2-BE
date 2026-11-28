package game.officestock.room;

import game.officestock.market.Market;
import game.officestock.market.NewsCatalog;
import game.officestock.market.StockCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 부장님 매매와 기분, 기분에 따른 순찰·판정. */
class BossTest {
    private static Market market() {
        var stocks = new StockCatalog(List.of(new StockCatalog.Stock("MINT", "민트식품", 10_000, 1_000_000, 0.01,
                List.of(), null, null, "식품")));
        var news = new NewsCatalog(List.of(new NewsCatalog.Card("MINT", 5, null, "호재", null)),
                new NewsCatalog.Rumors(List.of("{name} 좋다"), List.of("{name} 나쁘다"))).checkAgainst(stocks);
        return new Market(stocks, news, new SplittableRandom(1), 600);
    }

    @Test
    void 기분은_부장님_손익률로_정한다() {
        assertThat(Boss.Mood.of(-12)).isEqualTo(Boss.Mood.FURIOUS);
        assertThat(Boss.Mood.of(-4)).isEqualTo(Boss.Mood.ANNOYED);
        assertThat(Boss.Mood.of(0)).isEqualTo(Boss.Mood.NORMAL);
        assertThat(Boss.Mood.of(4)).isEqualTo(Boss.Mood.PLEASED);
        assertThat(Boss.Mood.of(9)).isEqualTo(Boss.Mood.ELATED);
    }

    @Test
    void 호재를_읽고_산_뒤_체결하면_손익과_기분이_따라온다() {
        var market = market();
        var boss = new Boss(20, new SplittableRandom(3));
        var item = new Market.NewsItem(1, "news", List.of("MINT"), "민트식품 · 호재", null, 10);
        // 70%는 방향을 맞힌다: 여러 번 읽으면 대부분 사겠다고 한다.
        long buys = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> boss.decide(item, Map.of("MINT", 10), Boss.Mood.NORMAL))
                .filter(d -> !d.isEmpty() && d.getFirst().buy()).count();
        assertThat(buys).isBetween(110L, 170L);

        var trade = boss.execute(new Boss.Decision("MINT", true, 0.2, null), market);
        assertThat(trade).isNotNull();
        assertThat(trade.quantity()).isPositive();
        assertThat(boss.wallet.holdings().get("MINT")).isEqualTo(trade.quantity());
        assertThat(boss.profitPercent(market)).isNegative();     // 사고팔기 비용과 가격 충격만큼은 손해
        // 가진 게 없으면 팔지 못한다.
        assertThat(boss.execute(new Boss.Decision("MINT", false, 1, null), market)).isNotNull();
        assertThat(boss.execute(new Boss.Decision("MINT", false, 1, null), market)).isNull();
    }

    @Test
    void 부장님은_자리에서_나와_점검하고_다시_자리로_돌아간다() {
        var player = UUID.randomUUID();
        var patrol = new Patrol();
        patrol.start(List.of(player));
        assertThat(patrol.atDesk).isTrue();
        patrol.seats.get(player).suspicion = Patrol.SUSPICION_MAX;   // 곧장 부른다
        patrol.advance(1);
        assertThat(patrol.atDesk).isFalse();
        assertThat(patrol.step(patrol.seats.get(player)).from()).isEqualTo(Patrol.DESK);
        int beat = 2;
        while (patrol.target() != null && beat < 30) patrol.advance(beat++);
        assertThat(patrol.seats.get(player).view).isEqualTo(Patrol.View.MEETING);
        while (!patrol.atDesk && beat < 60) patrol.advance(beat++);
        assertThat(patrol.atDesk).isTrue();
        assertThat(patrol.step(patrol.seats.get(player)).to()).isEqualTo(Patrol.DESK);
    }

    @Test
    void 기분이_아주_좋으면_한_사람당_한_번_봐준다() {
        var player = UUID.randomUUID();
        var patrol = new Patrol();
        patrol.start(List.of(player));
        patrol.mood = Boss.Mood.ELATED;
        var seat = patrol.seats.get(player);
        int beat = 1;
        for (int round = 0; round < 2; round++) {
            seat.view = Patrol.View.MARKET;
            seat.suspicion = Patrol.SUSPICION_MAX;     // 곧장 와서 주식 창을 본다
            while (patrol.justForgiven.isEmpty() && patrol.justCalled.isEmpty() && beat < 200) {
                seat.view = seat.view == Patrol.View.MEETING ? seat.view : Patrol.View.MARKET;
                patrol.advance(beat++);
            }
            if (round == 0) {
                assertThat(patrol.justForgiven).containsExactly(player);
                assertThat(patrol.justCalled).isEmpty();
                patrol.justForgiven.clear();
            } else {
                assertThat(patrol.justCalled).containsExactly(player);   // 두 번째는 봐주지 않는다
            }
        }
    }

    @Test
    void 기분이_아주_나쁘면_잘못이_없어도_데려갈_때가_있다() {
        int called = 0;
        for (int trial = 0; trial < 60; trial++) {
            var player = UUID.randomUUID();
            var patrol = new Patrol();
            patrol.start(List.of(player));
            patrol.mood = Boss.Mood.FURIOUS;
            patrol.hearMic(player, 90, 0);
            patrol.hearMic(player, 90, 1_000);   // 떠들어서 부른다(화면은 업무 창, 의심 0)
            for (int beat = 1; beat < 30 && patrol.target() != null || beat == 1; beat++) patrol.advance(beat);
            if (patrol.seats.get(player).view == Patrol.View.MEETING) {
                assertThat(patrol.seats.get(player).meetingReason).isEqualTo("mood");
                called++;
            }
        }
        assertThat(called).isBetween(10, 40);     // 약 40%
    }
}
