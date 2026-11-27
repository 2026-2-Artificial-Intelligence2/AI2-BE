package game.officestock.room;

import game.officestock.market.Market;
import game.officestock.player.WorkDesk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 퇴근 정산. 순자산 = 현금 + 보유 주식 청산가 − 비상 대출 원리금 − 신용 빚. 음수면 파산. */
final class Settlement {
    private Settlement() {}

    record TradeRecord(long second, String player, String symbol, String side,
                       long quantity, long amountWon) {}

    /** @param marginDebtWon 신용 매수 빚 @param bankrupt 순자산이 0 미만(파산) */
    record Standing(int rank, String player, long netWorthWon, long cashWon, long stockValueWon,
                    long loanRepayWon, long marginDebtWon, long workIncomeWon, long completedTasks,
                    Map<String, Long> holdings, boolean bankrupt) {}

    /**
     * 주식은 기준가가 아니라 "지금 혼자 전부 팔면 받을 금액"으로 평가한다.
     * 기준가로 평가하면 끝나기 직전에 크게 사서 가격을 올리는 것만으로 순자산이 늘기 때문이다.
     * 플레이어마다 같은 풀 상태에서 따로 계산하므로 순서에 따른 유불리가 없다.
     */
    /** 오늘의 직원상. @param award "scolded"(최다 훈계), "biggestLoss"(최대 손실), "hardestWorker"(업무왕) */
    record Award(String award, List<String> players, long value) {}

    /**
     * 오늘의 직원상: 가장 많이 훈계받은 사람, 가장 많이 잃은 사람, 업무를 가장 많이 한 사람.
     * 동점이면 모두 받는다. 해당하는 사람이 없으면(아무도 안 혼남, 아무도 안 잃음, 업무 0건) 그 상은 빠진다.
     * @param meetingsByPlayer 이름별 훈계 횟수 @param startingCashWon 출근 자금(손익 = 순자산 − 출근 자금)
     */
    static List<Award> awards(List<Standing> standings, Map<String, Integer> meetingsByPlayer,
                              long startingCashWon) {
        var awards = new ArrayList<Award>();
        top(standings, s -> meetingsByPlayer.getOrDefault(s.player(), 0)).ifPresent(a ->
                awards.add(new Award("scolded", a.players(), a.value())));
        top(standings, s -> startingCashWon - s.netWorthWon()).ifPresent(a ->
                awards.add(new Award("biggestLoss", a.players(), -a.value())));
        top(standings, Standing::completedTasks).ifPresent(a ->
                awards.add(new Award("hardestWorker", a.players(), a.value())));
        return awards;
    }

    /** 점수가 가장 큰(0보다 큰) 사람들. 순위표 순서를 따른다. */
    private static java.util.Optional<Award> top(List<Standing> standings,
                                                  java.util.function.ToLongFunction<Standing> score) {
        long best = 0;
        var players = new ArrayList<String>();
        for (var s : standings) {
            long value = score.applyAsLong(s);
            if (value <= 0 || value < best) continue;
            if (value > best) {
                best = value;
                players.clear();
            }
            players.add(s.player());
        }
        return players.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(new Award("", List.copyOf(players), best));
    }

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
            long net = balance.cashWon() + stockValue - balance.loanRepayWon() - balance.marginDebtWon();
            rows.add(new Standing(0, player, net, balance.cashWon(), stockValue, balance.loanRepayWon(),
                    balance.marginDebtWon(), balance.workIncomeWon(), balance.completedTasks(), holdings, net < 0));
        });
        rows.sort(Comparator.comparingLong(Standing::netWorthWon).reversed()
                .thenComparing(Standing::player));
        var ranked = new ArrayList<Standing>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            // 순자산이 같으면 같은 순위.
            int rank = i > 0 && row.netWorthWon() == rows.get(i - 1).netWorthWon()
                    ? ranked.get(i - 1).rank() : i + 1;
            ranked.add(new Standing(rank, row.player(), row.netWorthWon(), row.cashWon(), row.stockValueWon(),
                    row.loanRepayWon(), row.marginDebtWon(), row.workIncomeWon(), row.completedTasks(),
                    row.holdings(), row.bankrupt()));
        }
        return ranked;
    }
}
