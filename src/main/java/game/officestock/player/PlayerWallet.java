package game.officestock.player;

import game.officestock.market.StockPool;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

public final class PlayerWallet {
    private long cashWon;
    private long workIncomeWon;
    private long completedTasks;
    private final Map<String, Long> holdings = new HashMap<>();
    /** 종목별 지금 들고 있는 주식의 매수 원가(평균법). 일부를 팔면 판 비율만큼 줄어든다. */
    private final Map<String, Long> costBasis = new HashMap<>();
    /** 판 주식에서 확정된 손익의 합. */
    private long realizedWon;
    /** 비상 대출을 받았으면 퇴근 때 갚을 원리금. 0이면 아직 안 받았다. 한 판에 한 번. */
    private long loanRepayWon;
    /** 걸어 둔 지정가 매수 주문에 묶인 현금(쓸 수 있는 현금 cashWon과 따로 센다). */
    private long reservedWon;
    /** 종목별 신용 매수 빚(이자가 붙으면 늘어난다). 그 종목을 팔 때 먼저 갚는다. */
    private final Map<String, Long> marginDebt = new HashMap<>();
    /** 걸어 둔 지정가 매도 주문에 묶인 주식(보유량에는 포함되지만 따로 팔 수 없다). */
    private final Map<String, Long> lockedShares = new HashMap<>();

    /** 거래 비용(사고팔 때 각각, 1bp = 0.01%). 몇 초짜리 단타의 확실한 이득을 깎는다. */
    private final int feeBps;

    public Map<String, Long> holdings() {
        return Map.copyOf(holdings);
    }

    /** 체결 결과. grossWon은 풀과 주고받은 금액, feeWon은 거래 비용. */
    /**
     * @param debtRepaidWon 매도 대금에서 먼저 갚은 신용 빚(매수면 0)
     * @param borrowedWon 신용 매수로 빌린 금액(매도면 0)
     */
    public record Fill(long grossWon, long feeWon, long debtRepaidWon, long borrowedWon) {
        /** 매수면 낸 돈(대금+비용), 매도면 받은 돈(대금−비용). */
        public long netWon(boolean buy) {
            return buy ? grossWon + feeWon : grossWon - feeWon;
        }
    }

    /** 금액에 붙는 거래 비용(원 단위 올림). */
    public long feeFor(long amountWon) {
        return (Math.multiplyExact(amountWon, (long) feeBps) + 9_999) / 10_000;
    }

    public int feeBps() {
        return feeBps;
    }

    public Fill buy(String symbol, StockPool pool, long quantity) {
        return buy(symbol, pool, quantity, 1);
    }

    /** 신용 매수에 내 돈으로 내야 하는 몫(대금+비용을 배수로 나눠 올림). */
    public static long ownShare(long totalWon, int leverage) {
        return (totalWon + leverage - 1) / leverage;
    }

    /**
     * 매수. leverage가 2 이상이면 신용 매수다: 대금+비용 중 1/배수만 현금에서 내고 나머지는 이 종목의 신용 빚으로 빌린다.
     */
    public Fill buy(String symbol, StockPool pool, long quantity, int leverage) {
        if (quantity <= 0) throw new IllegalArgumentException("수량은 양수여야 합니다.");
        if (leverage < 1) throw new IllegalArgumentException("배수를 확인하세요.");
        long nextHolding = Math.addExact(holdings.getOrDefault(symbol, 0L), quantity);
        long quote = pool.quoteBuy(quantity);
        if (quote == Long.MAX_VALUE) throw new IllegalArgumentException("매수할 수 없는 수량입니다.");
        long fee = feeFor(quote);
        long total = Math.addExact(quote, fee);
        long own = ownShare(total, leverage);
        if (own > cashWon) throw new IllegalArgumentException(leverage == 1
                ? "매수할 현금이 부족합니다(거래 비용 포함)." : "신용 매수에 낼 내 돈(1/" + leverage + ")이 부족합니다.");
        long cost = pool.buy(quantity, Long.MAX_VALUE);
        long borrowed = cost + fee - own;
        cashWon -= own;
        if (borrowed > 0) marginDebt.merge(symbol, borrowed, Math::addExact);
        holdings.put(symbol, nextHolding);
        costBasis.merge(symbol, cost + fee, Math::addExact);
        return new Fill(cost, fee, 0, borrowed);
    }

    /** 이 수량을 지금 혼자 전부 팔면 받을 금액(거래 비용 뺀 뒤). 손익과 퇴근 정산의 기준이다. */
    public long liquidationValue(StockPool pool, long shares) {
        long gross = pool.quoteSell(shares);
        return gross - feeFor(gross);
    }

    /** 지정가 매도로 묶이지 않아 지금 팔 수 있는 수량. */
    public long sellable(String symbol) {
        return holdings.getOrDefault(symbol, 0L) - lockedShares.getOrDefault(symbol, 0L);
    }

    public void reserveCash(long amountWon) {
        if (amountWon <= 0) throw new IllegalArgumentException("주문 금액을 확인하세요.");
        if (amountWon > cashWon) throw new IllegalArgumentException("주문에 묶을 현금이 부족합니다.");
        cashWon -= amountWon;
        reservedWon += amountWon;
    }

    public void releaseCash(long amountWon) {
        reservedWon -= amountWon;
        cashWon += amountWon;
    }

    public void lockShares(String symbol, long quantity) {
        if (quantity <= 0 || quantity > sellable(symbol))
            throw new IllegalArgumentException("주문에 묶을 주식이 부족합니다(지정가 매도에 이미 묶인 주식은 뺍니다).");
        lockedShares.merge(symbol, quantity, Long::sum);
    }

    public void unlockShares(String symbol, long quantity) {
        lockedShares.merge(symbol, -quantity, Long::sum);
    }

    /** 이 수량을 지금 팔면 같이 빠질 원가(sell과 같은 계산). 매도 수익률에 쓴다. */
    public long costOf(String symbol, long quantity) {
        long owned = holdings.getOrDefault(symbol, 0L);
        long basis = costBasis.getOrDefault(symbol, 0L);
        if (owned <= 0 || quantity <= 0) return 0;
        return quantity >= owned ? basis : Math.round((double) basis * quantity / owned);
    }

    public Fill sell(String symbol, StockPool pool, long quantity) {
        long owned = holdings.getOrDefault(symbol, 0L);
        if (quantity > sellable(symbol))
            throw new IllegalArgumentException("지정가 매도에 묶인 주식은 따로 팔 수 없습니다.");
        long gross = pool.sell(quantity, owned, Long.MAX_VALUE - Math.max(0, cashWon));
        long fee = feeFor(gross);
        long proceeds = gross - fee;
        // 신용 빚이 있으면 판 비율만큼 먼저 갚는다(다 팔면 전부). 모자라면 현금에서 마저 갚아 현금이 음수가 될 수 있다(파산).
        long debt = marginDebt.getOrDefault(symbol, 0L);
        long repaid = quantity == owned ? debt : Math.round((double) debt * quantity / owned);
        if (repaid > 0) {
            if (debt - repaid == 0) marginDebt.remove(symbol); else marginDebt.put(symbol, debt - repaid);
        }
        cashWon += proceeds - repaid;
        holdings.put(symbol, owned - quantity);
        long basis = costBasis.getOrDefault(symbol, 0L);
        // 판 주식의 원가 = 보유 원가 × 판 수량 / 보유 수량(반올림). 다 팔면 남은 원가 전부.
        long soldCost = quantity == owned ? basis
                : Math.round((double) basis * quantity / owned);
        costBasis.put(symbol, basis - soldCost);
        realizedWon += proceeds - soldCost;
        return new Fill(gross, fee, repaid, 0);
    }

    public PlayerWallet(long startingCashWon, int feeBps) {
        if (startingCashWon < 0) {
            throw new IllegalArgumentException("시작 자금은 음수일 수 없습니다.");
        }
        if (feeBps < 0) throw new IllegalArgumentException("거래 비용은 음수일 수 없습니다.");
        cashWon = startingCashWon;
        this.feeBps = feeBps;
    }

    public void rewardCompletedTask(long taskNumber, long rewardWon) {
        if (taskNumber <= 0 || rewardWon <= 0) {
            throw new IllegalArgumentException("업무 번호와 수당은 양수여야 합니다.");
        }
        if (taskNumber <= completedTasks) return;
        if (taskNumber != completedTasks + 1) {
            throw new IllegalArgumentException("현재 배정된 업무가 아닙니다.");
        }

        long nextCash = Math.addExact(cashWon, rewardWon);
        long nextIncome = Math.addExact(workIncomeWon, rewardWon);
        cashWon = nextCash;
        workIncomeWon = nextIncome;
        completedTasks = taskNumber;
    }

    /** 비상 대출: 현금을 받고, 퇴근 정산에서 원리금을 뺀다. 자격(순자산 기준)은 호출하는 쪽이 확인한다. */
    public void takeLoan(long amountWon, long repayWon) {
        if (loanRepayWon > 0) throw new IllegalStateException("비상 대출은 한 판에 한 번만 받을 수 있습니다.");
        if (amountWon <= 0 || repayWon < amountWon) throw new IllegalArgumentException("대출 조건을 확인하세요.");
        cashWon = Math.addExact(cashWon, amountWon);
        loanRepayWon = repayWon;
    }

    public long loanRepayWon() {
        return loanRepayWon;
    }

    /** @param cashWon 지금 쓸 수 있는 현금 @param reservedWon 지정가 매수 주문에 묶인 현금 */
    /**
     * @param cashWon 지금 쓸 수 있는 현금(신용 빚을 갚느라 음수일 수 있다)
     * @param reservedWon 지정가 매수 주문에 묶인 현금
     * @param marginDebtWon 신용 매수 빚 합계
     */
    public record Balance(long cashWon, long workIncomeWon, long completedTasks, long loanRepayWon,
                          long reservedWon, long marginDebtWon) {}

    public Balance balance() {
        return new Balance(cashWon, workIncomeWon, completedTasks, loanRepayWon, reservedWon, totalMarginDebt());
    }

    public long marginDebt(String symbol) {
        return marginDebt.getOrDefault(symbol, 0L);
    }

    public long totalMarginDebt() {
        return marginDebt.values().stream().mapToLong(Long::longValue).sum();
    }

    /** 신용 빚이 있는 종목들. */
    public java.util.Set<String> marginSymbols() {
        return java.util.Set.copyOf(marginDebt.keySet());
    }

    /** 신용 빚에 이자를 붙인다(원 단위 올림). 1분마다 부른다. */
    public void accrueMarginInterest(int bpsPerMinute) {
        marginDebt.replaceAll((symbol, debt) -> debt + (Math.multiplyExact(debt, (long) bpsPerMinute) + 9_999) / 10_000);
    }

    /** 담보 비율(%) = (평가액 − 빚) ÷ 평가액. 평가액은 기준가 × 수량이다. 빚이 없으면 100. */
    public double marginRatioPercent(String symbol, long spotWon) {
        long debt = marginDebt(symbol);
        if (debt == 0) return 100;
        double value = (double) spotWon * holdings.getOrDefault(symbol, 0L);
        return value <= 0 ? -100 : (value - debt) * 100 / value;
    }

    /**
     * 보유 종목 하나의 현황. 평가는 퇴근 정산과 같은 청산가(지금 혼자 전부 팔면 받을 금액)다.
     * @param avgPriceWon 평균 매수가(원가 ÷ 수량, 내림)
     * @param profitWon 청산가 − 원가
     * @param profitPercent 원가 대비 손익률(%), 소수 첫째 자리
     */
    /** @param debtWon 이 종목의 신용 빚 @param marginRatioPercent 담보 비율(%), 빚이 없으면 100 */
    public record Position(long shares, long costWon, long avgPriceWon, long valueWon,
                           long profitWon, double profitPercent, long debtWon, double marginRatioPercent) {}

    public record Portfolio(Map<String, Position> positions, long realizedWon) {}

    /** @param pools 종목 코드로 풀을 찾는다. 청산가 계산에 쓴다. */
    public Portfolio portfolio(Function<String, StockPool> pools) {
        var positions = new TreeMap<String, Position>();
        holdings.forEach((symbol, shares) -> {
            if (shares <= 0) return;
            long cost = costBasis.getOrDefault(symbol, 0L);
            long value = liquidationValue(pools.apply(symbol), shares);
            double percent = cost == 0 ? 0 : Math.round((value - cost) * 1000.0 / cost) / 10.0;
            var pool = pools.apply(symbol);
            long spot = pool.reserves().cashWon() / pool.reserves().shares();
            positions.put(symbol, new Position(shares, cost, cost / shares, value, value - cost, percent,
                    marginDebt(symbol), Math.round(marginRatioPercent(symbol, spot) * 10) / 10.0));
        });
        return new Portfolio(positions, realizedWon);
    }
}
