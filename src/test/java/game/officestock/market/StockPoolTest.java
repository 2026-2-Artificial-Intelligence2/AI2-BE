package game.officestock.market;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 상수곱 풀: 시세표·예상 금액·체결이 모두 이 계산을 따른다. */
class StockPoolTest {
    @Test
    void 예상_매수금액과_실제_체결금액이_같다() {
        var pool = new StockPool(1_000, 10_000_000);
        long quote = pool.quoteBuy(50);
        assertThat(pool.buy(50, Long.MAX_VALUE)).isEqualTo(quote);
        assertThat(pool.reserves().shares()).isEqualTo(950);
    }

    @Test
    void 큰_주문일수록_주당_가격이_불리하다() {
        var pool = new StockPool(1_000, 10_000_000);
        double small = pool.quoteBuy(10) / 10.0, big = pool.quoteBuy(200) / 200.0;
        assertThat(big).isGreaterThan(small);
        assertThat(small).isGreaterThan(10_000);
    }

    @Test
    void 사고_같은_수량을_되팔면_풀이_거의_제자리로_돌아온다() {
        var pool = new StockPool(1_000, 10_000_000);
        long paid = pool.buy(100, Long.MAX_VALUE);
        long got = pool.sell(100, 100);
        assertThat(pool.reserves().shares()).isEqualTo(1_000);
        // 원 단위 올림 때문에 판 쪽이 몇 원 손해 볼 수 있지만 이득은 없다.
        assertThat(got).isLessThanOrEqualTo(paid).isGreaterThan(paid - 5);
    }

    @Test
    void 예산_안에서_살_수_있는_최대_수량() {
        var pool = new StockPool(1_000, 10_000_000);
        long n = pool.maxBuyable(1_000_000);
        assertThat(pool.quoteBuy(n)).isLessThanOrEqualTo(1_000_000);
        assertThat(pool.quoteBuy(n + 1)).isGreaterThan(1_000_000);
    }

    @Test
    void 현금이_모자라거나_풀을_다_사려_하면_거절한다() {
        var pool = new StockPool(1_000, 10_000_000);
        assertThatThrownBy(() -> pool.buy(10, 1_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pool.buy(1_000, Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(pool.reserves().shares()).isEqualTo(1_000);
    }

    @Test
    void 목표가_쪽으로_차이의_일부만큼_움직인다() {
        var pool = new StockPool(1_000, 10_000_000);
        pool.moveToward(12_000, 0.5);
        assertThat(pool.reserves().cashWon() / pool.reserves().shares()).isEqualTo(11_000);
    }
}
