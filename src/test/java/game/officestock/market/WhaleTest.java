package game.officestock.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** 솔로의 보이지 않는 세력: 매번 사 모은(던진) 만큼 정확히 되돌리고, 퇴근 직전에는 새로 오지 않는다. */
class WhaleTest {
    @Test
    void 한_번_들어오면_쌓은_수량을_모두_되돌린다() {
        for (int seed = 0; seed < 50; seed++) {
            var random = new SplittableRandom(seed);
            long session = 600;
            var whale = new Whale(session, random);
            long position = 0, episodes = 0;
            String symbol = null;
            for (long tick = 1; tick <= session; tick++) {
                var order = whale.advance(tick, List.of("A", "B", "C"), s -> 10_000, random);
                whale.scheduleNext(tick, random);
                if (order == null) continue;
                if (position == 0) {
                    episodes++;
                    symbol = order.symbol();
                    assertThat(tick).as("퇴근 45초 전 이후에는 시작하지 않는다").isLessThanOrEqualTo(session - 45);
                }
                assertThat(order.symbol()).isEqualTo(symbol);
                position += order.quantity();
            }
            assertThat(position).as("seed " + seed + " 판이 끝나면 포지션이 0").isZero();
            assertThat(episodes).isBetween(2L, 8L);
        }
    }

    @Test
    void 세력이_있는_시장도_판이_끝날_때까지_정상으로_돈다() {
        var stocks = new StockCatalog(List.of(
                new StockCatalog.Stock("A", "에이", 10_000, 10_000, 0.01, List.of(), null, null, "업종"),
                new StockCatalog.Stock("B", "비", 20_000, 5_000, 0.01, List.of(), null, null, "업종")));
        var news = new NewsCatalog(List.of(new NewsCatalog.Card("A", 5, null, "a", null),
                new NewsCatalog.Card("B", -5, null, "b", null)),
                new NewsCatalog.Rumors(List.of("{name} 좋다"), List.of("{name} 나쁘다"))).checkAgainst(stocks);
        var market = new Market(stocks, news, new SplittableRandom(7), 600, true);
        long volume = 0;
        for (long t = 1; t <= 600; t++) market.advance(t);
        for (var candles : market.chart().all().values())
            for (var c : candles) if (c.startSecond() >= 0) volume += c.volume();
        assertThat(volume).as("세력 매매가 거래량으로 찍힌다").isPositive();
        assertThat(market.spotPrice("A")).isPositive();
    }
}
