package game.officestock.player;

import game.officestock.market.StockPool;
import java.util.HashMap;
import java.util.Map;

public final class PlayerWallet {
    private long cashWon;
    private long workIncomeWon;
    private long completedTasks;
    private final Map<String, Long> holdings = new HashMap<>();

    public Map<String, Long> holdings() {
        return Map.copyOf(holdings);
    }

    public long buy(String symbol, StockPool pool, long quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("수량은 양수여야 합니다.");
        long nextHolding = Math.addExact(holdings.getOrDefault(symbol, 0L), quantity);
        long cost = pool.buy(quantity, cashWon);
        cashWon -= cost;
        holdings.put(symbol, nextHolding);
        return cost;
    }

    public long sell(String symbol, StockPool pool, long quantity) {
        long owned = holdings.getOrDefault(symbol, 0L);
        long proceeds = pool.sell(quantity, owned, Long.MAX_VALUE - cashWon);
        cashWon += proceeds;
        holdings.put(symbol, owned - quantity);
        return proceeds;
    }

    public PlayerWallet(long startingCashWon) {
        if (startingCashWon < 0) {
            throw new IllegalArgumentException("시작 자금은 음수일 수 없습니다.");
        }
        cashWon = startingCashWon;
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

    public record Balance(long cashWon, long workIncomeWon, long completedTasks) {}

    public Balance balance() {
        return new Balance(cashWon, workIncomeWon, completedTasks);
    }
}
