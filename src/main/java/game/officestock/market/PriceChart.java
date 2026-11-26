package game.officestock.market;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 종목별 가격 봉(캔들) 기록. 매초 시세와 플레이어 체결을 받아 CANDLE_SECONDS 단위로 묶는다. */
public final class PriceChart {
    public static final int CANDLE_SECONDS = 10;

    /**
     * @param startSecond 봉이 시작한 판 경과 초(0, 10, 20, …)
     * @param volume 이 봉 동안 플레이어들이 사고판 주식 수
     */
    public record Candle(long startSecond, long open, long high, long low, long close, long volume) {}

    private final Map<String, List<Candle>> candles = new LinkedHashMap<>();

    /** 출근 전 봉을 채운다. 게임 시작 전, 다른 기록보다 먼저 부른다. */
    void addHistory(String symbol, List<Candle> history) {
        candles.computeIfAbsent(symbol, s -> new ArrayList<>()).addAll(history);
    }

    /** 시세나 체결을 반영한다. 같은 봉이면 고가·저가·종가·거래량을 고치고, 새 봉이면 하나를 붙인다. */
    void record(String symbol, long second, long priceWon, long volume) {
        var list = candles.computeIfAbsent(symbol, s -> new ArrayList<>());
        long start = second / CANDLE_SECONDS * CANDLE_SECONDS;
        if (!list.isEmpty() && list.getLast().startSecond() == start) {
            var c = list.getLast();
            list.set(list.size() - 1, new Candle(start, c.open(), Math.max(c.high(), priceWon),
                    Math.min(c.low(), priceWon), priceWon, c.volume() + volume));
        } else {
            // 새 봉은 직전 종가에서 시작해 봉 사이가 끊기지 않게 한다.
            long open = list.isEmpty() ? priceWon : list.getLast().close();
            list.add(new Candle(start, open, Math.max(open, priceWon), Math.min(open, priceWon), priceWon, volume));
        }
    }

    /** 종목별 전체 봉(복사본). 게임 시작·재접속 때 보낸다. */
    public Map<String, List<Candle>> all() {
        var copy = new LinkedHashMap<String, List<Candle>>();
        candles.forEach((symbol, list) -> copy.put(symbol, List.copyOf(list)));
        return copy;
    }

    /** 종목별 마지막(진행 중인) 봉. 매초 시세 메시지에 실어 보낸다. */
    public Map<String, Candle> latest() {
        var last = new LinkedHashMap<String, Candle>();
        candles.forEach((symbol, list) -> last.put(symbol, list.getLast()));
        return last;
    }
}
