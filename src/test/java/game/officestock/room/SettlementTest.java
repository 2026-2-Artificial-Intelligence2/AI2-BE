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

    @Test
    void 직원상은_최다_훈계_최대_손실_업무왕이고_동점이면_함께_받는다() {
        var market = market();
        var winner = new WorkDesk(1_000_000, 35_000, 20, 0);
        var loser = new WorkDesk(1_000_000, 35_000, 20, 0);
        loser.wallet().takeLoan(100_000, 500_000);
        var worker = new WorkDesk(1_000_000, 35_000, 20, 0);
        worker.wallet().rewardCompletedTask(1, 35_000);
        worker.wallet().rewardCompletedTask(2, 35_000);
        var standings = Settlement.standings(new LinkedHashMap<>(Map.of("가", winner, "나", loser, "다", worker)), market);

        var awards = Settlement.awards(standings, Map.of("가", 2, "나", 2, "다", 0), 1_000_000);

        assertThat(awards).extracting(Settlement.Award::award)
                .containsExactly("scolded", "biggestLoss", "hardestWorker");
        assertThat(awards.get(0).players()).containsExactlyInAnyOrder("가", "나");
        assertThat(awards.get(0).value()).isEqualTo(2);
        assertThat(awards.get(1).players()).containsExactly("나");
        assertThat(awards.get(1).value()).isEqualTo(-400_000);
        assertThat(awards.get(2).players()).containsExactly("다");
        assertThat(awards.get(2).value()).isEqualTo(2);
    }

    @Test
    void 해당자가_없는_직원상은_빠진다() {
        var market = market();
        var a = new WorkDesk(1_000, 35_000, 20, 0);
        var standings = Settlement.standings(new LinkedHashMap<>(Map.of("가", a)), market);
        assertThat(Settlement.awards(standings, Map.of(), 1_000)).isEmpty();
    }

    @Test
    void 매도는_실현_수익률을_매수는_퇴근_시세_수익률을_채운다() {
        var market = market();
        var sold = new Settlement.TradeRecord(10, "나", "MINT", "매도", 10, 110_000, 10.0);
        var bought = new Settlement.TradeRecord(20, "나", "MINT", "신용 2배 매수", 10, 100_200);
        var trades = Settlement.withClosingReturns(List.of(sold, bought), market, 20);

        assertThat(trades.get(0).returnPercent()).isEqualTo(10.0);   // 매도는 그대로
        long gross = market.spotPrice("MINT") * 10;
        long net = gross - (gross * 20 + 9_999) / 10_000;
        assertThat(trades.get(1).returnPercent()).isEqualTo(Settlement.returnPercent(net, 100_200));
        assertThat(Settlement.returnPercent(110_000, 100_000)).isEqualTo(10.0);
        assertThat(Settlement.returnPercent(5, 0)).isNull();
    }
}
