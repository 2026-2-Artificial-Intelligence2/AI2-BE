package game.officestock.room;

import game.officestock.market.Market;
import game.officestock.market.NewsCatalog;
import game.officestock.market.StockCatalog;
import game.officestock.player.WorkDesk;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** 퇴근 정산: 순자산 = 현금 + 청산가 − 대출 원리금 − 신용 빚. */
class SettlementTest {
    private static Market market() {
        var stocks = new StockCatalog(List.of(new StockCatalog.Stock("MINT", "민트식품", 10_000, 1_000, 0.01,
                List.of(), null, null, "식품")));
        var news = new NewsCatalog(List.of(new NewsCatalog.Card("MINT", 5, null, "호재", null)),
                new NewsCatalog.Rumors(List.of("{name} 좋다"), List.of("{name} 나쁘다"))).checkAgainst(stocks);
        return new Market(stocks, news, new SplittableRandom(1), 600);
    }

    @Test
    void 순자산은_청산가로_평가하고_대출과_빚을_뺀다() {
        var market = market();
        var rich = new WorkDesk(10_000_000, 35_000, 20, 0);
        rich.wallet().buy("MINT", market.pool("MINT"), 20, 2);
        rich.wallet().takeLoan(1_000_000, 1_200_000);
        var plain = new WorkDesk(10_000_000, 35_000, 20, 0);
        var desks = new LinkedHashMap<String, WorkDesk>(Map.of("부자", rich, "평범", plain));

        var standings = Settlement.standings(desks, market);
        var richRow = standings.stream().filter(s -> s.player().equals("부자")).findFirst().orElseThrow();
        long liquidation = rich.wallet().liquidationValue(market.pool("MINT"), 20);
        assertThat(richRow.stockValueWon()).isEqualTo(liquidation);
        assertThat(richRow.netWorthWon()).isEqualTo(rich.wallet().balance().cashWon() + liquidation
                - 1_200_000 - rich.wallet().marginDebt("MINT"));
        // 사고팔기만 해도 거래 비용과 가격 충격만큼 손해라, 아무것도 안 한 사람이 1위다.
        assertThat(standings.getFirst().player()).isEqualTo("평범");
        assertThat(standings.getFirst().rank()).isEqualTo(1);
    }

    @Test
    void 순자산이_같으면_같은_순위이고_음수면_파산이다() {
        var market = market();
        var a = new WorkDesk(1_000, 35_000, 20, 0);
        var b = new WorkDesk(1_000, 35_000, 20, 0);
        var broke = new WorkDesk(0, 35_000, 20, 0);
        broke.wallet().takeLoan(100, 1_000);
        var standings = Settlement.standings(new LinkedHashMap<>(Map.of("가", a, "나", b, "빚쟁이", broke)), market);
        assertThat(standings.get(0).rank()).isEqualTo(1);
        assertThat(standings.get(1).rank()).isEqualTo(1);
        var last = standings.get(2);
        assertThat(last.player()).isEqualTo("빚쟁이");
        assertThat(last.bankrupt()).isTrue();
    }
}
