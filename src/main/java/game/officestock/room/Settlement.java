package game.officestock.room;

import game.officestock.market.Market;
import game.officestock.player.WorkDesk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 퇴근 정산. 순자산 = 현금 + 보유 주식 청산가 − 비상 대출 원리금. */
final class Settlement {
    private Settlement() {}

    record TradeRecord(long second, String player, String symbol, String side,
                       long quantity, long amountWon) {}

    record Standing(int rank, String player, long netWorthWon, long cashWon, long stockValueWon,
                    long loanRepayWon, long workIncomeWon, long completedTasks, Map<String, Long> holdings) {}

    /**
     * 주식은 기준가가 아니라 "지금 혼자 전부 팔면 받을 금액"으로 평가한다.
     * 기준가로 평가하면 끝나기 직전에 크게 사서 가격을 올리는 것만으로 순자산이 늘기 때문이다.
     * 플레이어마다 같은 풀 상태에서 따로 계산하므로 순서에 따른 유불리가 없다.
     */
    static List<Standing> standings(Map<String, WorkDesk> desksByPlayer, Market market) {
        var rows = new ArrayList<Standing>();
        desksByPlayer.forEach((player, desk) -> {
            var balance = desk.wallet().balance();
            var holdings = new TreeMap<String, Long>();
            long stockValue = 0;
            for (var entry : desk.wallet().holdings().entrySet()) {
                if (entry.getValue() <= 0) continue;
                holdings.put(entry.getKey(), entry.getValue());
                stockValue += desk.wallet().liquidationValue(market.pool(entry.getKey()), entry.getValue());
            }
            rows.add(new Standing(0, player, balance.cashWon() + stockValue - balance.loanRepayWon(),
                    balance.cashWon(), stockValue, balance.loanRepayWon(), balance.workIncomeWon(),
                    balance.completedTasks(), holdings));
        });
        rows.sort(Comparator.comparingLong(Standing::netWorthWon).reversed()
                .thenComparing(Standing::player));
        var ranked = new ArrayList<Standing>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            // 순자산이 같으면 같은 순위.
            int rank = i > 0 && row.netWorthWon() == rows.get(i - 1).netWorthWon()
                    ? ranked.get(i - 1).rank() : i + 1;
            ranked.add(new Standing(rank, row.player(), row.netWorthWon(), row.cashWon(),
                    row.stockValueWon(), row.loanRepayWon(), row.workIncomeWon(), row.completedTasks(),
                    row.holdings()));
        }
        return ranked;
    }
}
