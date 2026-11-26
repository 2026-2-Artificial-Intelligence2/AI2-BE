package game.officestock.player;

import game.officestock.market.StockPool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 지갑: 거래 비용, 신용 매수와 빚 상환, 대출, 업무 수당. */
class PlayerWalletTest {
    private static final int FEE_BPS = 20;

    @Test
    void 거래_비용은_0_2퍼센트를_원_단위로_올린다() {
        var wallet = new PlayerWallet(10_000_000, FEE_BPS);
        assertThat(wallet.feeFor(10_000)).isEqualTo(20);
        assertThat(wallet.feeFor(10_001)).isEqualTo(21);
        assertThat(wallet.feeFor(0)).isZero();
    }

    @Test
    void 매수하면_대금과_비용만큼_현금이_줄고_주식이_는다() {
        var wallet = new PlayerWallet(10_000_000, FEE_BPS);
        var pool = new StockPool(1_000, 10_000_000);
        long quote = pool.quoteBuy(10);
        var fill = wallet.buy("MINT", pool, 10);
        assertThat(fill.grossWon()).isEqualTo(quote);
        assertThat(wallet.balance().cashWon()).isEqualTo(10_000_000 - quote - wallet.feeFor(quote));
        assertThat(wallet.holdings()).containsEntry("MINT", 10L);
    }

    @Test
    void 현금보다_비싸면_사지_못하고_아무것도_바뀌지_않는다() {
        var wallet = new PlayerWallet(50_000, FEE_BPS);
        var pool = new StockPool(1_000, 10_000_000);
        assertThatThrownBy(() -> wallet.buy("MINT", pool, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThat(wallet.balance().cashWon()).isEqualTo(50_000);
        assertThat(pool.reserves().shares()).isEqualTo(1_000);
    }

    @Test
    void 신용_3배_매수는_3분의_1만_내고_나머지는_빚이_된다() {
        var wallet = new PlayerWallet(10_000_000, FEE_BPS);
        var pool = new StockPool(1_000, 10_000_000);
        long total = pool.quoteBuy(30);
        total += wallet.feeFor(total);
        var fill = wallet.buy("MINT", pool, 30, 3);
        long own = PlayerWallet.ownShare(total, 3);
        assertThat(wallet.balance().cashWon()).isEqualTo(10_000_000 - own);
        assertThat(fill.borrowedWon()).isEqualTo(total - own);
        assertThat(wallet.marginDebt("MINT")).isEqualTo(total - own);
    }

    @Test
    void 신용_주식을_다_팔면_빚을_먼저_갚는다() {
        var wallet = new PlayerWallet(10_000_000, FEE_BPS);
        var pool = new StockPool(1_000, 10_000_000);
        wallet.buy("MINT", pool, 30, 3);
        long debt = wallet.marginDebt("MINT");
        long cashBefore = wallet.balance().cashWon();
        var fill = wallet.sell("MINT", pool, 30);
        assertThat(fill.debtRepaidWon()).isEqualTo(debt);
        assertThat(wallet.marginDebt("MINT")).isZero();
        assertThat(wallet.balance().cashWon()).isEqualTo(cashBefore + fill.grossWon() - fill.feeWon() - debt);
    }

    @Test
    void 신용_이자는_1분마다_빚에_붙는다() {
        var wallet = new PlayerWallet(10_000_000, FEE_BPS);
        var pool = new StockPool(1_000, 10_000_000);
        wallet.buy("MINT", pool, 30, 3);
        long debt = wallet.marginDebt("MINT");
        wallet.accrueMarginInterest(10);
        assertThat(wallet.marginDebt("MINT")).isEqualTo(debt + (debt * 10 + 9_999) / 10_000);
    }

    @Test
    void 비상_대출은_한_판에_한_번뿐이다() {
        var wallet = new PlayerWallet(10_000_000, FEE_BPS);
        wallet.takeLoan(1_000_000, 1_200_000);
        assertThat(wallet.balance().cashWon()).isEqualTo(11_000_000);
        assertThat(wallet.loanRepayWon()).isEqualTo(1_200_000);
        assertThatThrownBy(() -> wallet.takeLoan(1_000_000, 1_200_000)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 업무_수당은_순서대로_한_번씩만_준다() {
        var wallet = new PlayerWallet(0, FEE_BPS);
        wallet.rewardCompletedTask(1, 35_000);
        wallet.rewardCompletedTask(1, 35_000);
        assertThat(wallet.balance().cashWon()).isEqualTo(35_000);
        assertThatThrownBy(() -> wallet.rewardCompletedTask(3, 35_000)).isInstanceOf(IllegalArgumentException.class);
    }
}
