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
    /** 걸어 둔 지정가 매도 주문에 묶인 주식(보유량에는 포함되지만 따로 팔 수 없다). */
    private final Map<String, Long> lockedShares = new HashMap<>();

    /** 거래 비용(사고팔 때 각각, 1bp = 0.01%). 몇 초짜리 단타의 확실한 이득을 깎는다. */
    private final int feeBps;

    public Map<String, Long> holdings() {
        return Map.copyOf(holdings);
    }

    /** 체결 결과. grossWon은 풀과 주고받은 금액, feeWon은 거래 비용. */
    public record Fill(long grossWon, long feeWon) {
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
        if (quantity <= 0) throw new IllegalArgumentException("수량은 양수여야 합니다.");
        long nextHolding = Math.addExact(holdings.getOrDefault(symbol, 0L), quantity);
        long quote = pool.quoteBuy(quantity);
        if (quote == Long.MAX_VALUE) throw new IllegalArgumentException("매수할 수 없는 수량입니다.");
        long fee = feeFor(quote);
        if (Math.addExact(quote, fee) > cashWon) throw new IllegalArgumentException("매수할 현금이 부족합니다(거래 비용 포함).");
        long cost = pool.buy(quantity, Long.MAX_VALUE);
        cashWon -= cost + fee;
        holdings.put(symbol, nextHolding);
        costBasis.merge(symbol, cost + fee, Math::addExact);
        return new Fill(cost, fee);
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

    public Fill sell(String symbol, StockPool pool, long quantity) {
        long owned = holdings.getOrDefault(symbol, 0L);
        if (quantity > sellable(symbol))
            throw new IllegalArgumentException("지정가 매도에 묶인 주식은 따로 팔 수 없습니다.");
        long gross = pool.sell(quantity, owned, Long.MAX_VALUE - cashWon);
        long fee = feeFor(gross);
        long proceeds = gross - fee;
        cashWon += proceeds;
        holdings.put(symbol, owned - quantity);
        long basis = costBasis.getOrDefault(symbol, 0L);
        // 판 주식의 원가 = 보유 원가 × 판 수량 / 보유 수량(반올림). 다 팔면 남은 원가 전부.
        long soldCost = quantity == owned ? basis
                : Math.round((double) basis * quantity / owned);
        costBasis.put(symbol, basis - soldCost);
        realizedWon += proceeds - soldCost;
        return new Fill(gross, fee);
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
    public record Balance(long cashWon, long workIncomeWon, long completedTasks, long loanRepayWon,
                          long reservedWon) {}

    public Balance balance() {
        return new Balance(cashWon, workIncomeWon, completedTasks, loanRepayWon, reservedWon);
    }

    /**
     * 보유 종목 하나의 현황. 평가는 퇴근 정산과 같은 청산가(지금 혼자 전부 팔면 받을 금액)다.
     * @param avgPriceWon 평균 매수가(원가 ÷ 수량, 내림)
     * @param profitWon 청산가 − 원가
     * @param profitPercent 원가 대비 손익률(%), 소수 첫째 자리
     */
    public record Position(long shares, long costWon, long avgPriceWon, long valueWon,
                           long profitWon, double profitPercent) {}

    public record Portfolio(Map<String, Position> positions, long realizedWon) {}

    /** @param pools 종목 코드로 풀을 찾는다. 청산가 계산에 쓴다. */
    public Portfolio portfolio(Function<String, StockPool> pools) {
        var positions = new TreeMap<String, Position>();
        holdings.forEach((symbol, shares) -> {
            if (shares <= 0) return;
            long cost = costBasis.getOrDefault(symbol, 0L);
            long value = liquidationValue(pools.apply(symbol), shares);
            double percent = cost == 0 ? 0 : Math.round((value - cost) * 1000.0 / cost) / 10.0;
            positions.put(symbol, new Position(shares, cost, cost / shares, value, value - cost, percent));
        });
        return new Portfolio(positions, realizedWon);
    }
}
