package game.officestock.market;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 출근 전 주가 흐름. stocks.json의 story 구간을 따라 과거 10초 봉을 만든다.
 * 구간마다 처음과 끝 가격을 정해 두고 그 사이를 양끝이 고정된 랜덤 걸음(브라운 다리)으로 잇는다.
 * 그래서 큰 흐름은 판마다 같고 잔물결만 달라지며, 마지막 봉의 종가는 정확히 시작가다.
 */
final class PriceStory {
    private PriceStory() {}

    /** 지난 소식 표시. second는 그 구간이 시작한 판 경과 초(음수). */
    record Note(long second, String text) {}

    record Result(List<PriceChart.Candle> candles, List<Note> notes) {}

    /** 기초 종목의 출근 전 봉을 배수로 따라 레버리지 종목의 봉을 만든다. 마지막 종가는 startPriceWon. */
    static List<PriceChart.Candle> follow(List<PriceChart.Candle> base, double leverage, long startPriceWon,
                                          RandomGenerator random) {
        var history = base.stream().filter(c -> c.startSecond() < 0).toList();
        if (history.isEmpty()) return List.of();
        double[] close = new double[history.size()];
        double previousBase = history.getFirst().open(), level = 1;
        for (int i = 0; i < history.size(); i++) {
            var c = history.get(i);
            level *= Math.max(0.05, 1 + leverage * (c.close() / previousBase - 1));
            previousBase = c.close();
            close[i] = level;
        }
        double scale = startPriceWon / close[close.length - 1];
        var out = new ArrayList<PriceChart.Candle>();
        double previous = scale;
        for (int i = 0; i < history.size(); i++) {
            var c = history.get(i);
            double now = close[i] * scale;
            // 기초 종목 봉의 꼬리 길이를 배수만큼 키워 쓴다(인버스면 위아래를 바꾼다).
            double up = (double) c.high() / Math.max(c.open(), c.close()) - 1;
            double down = 1 - (double) c.low() / Math.min(c.open(), c.close());
            double wickUp = Math.abs(leverage) * (leverage > 0 ? up : down);
            double wickDown = Math.abs(leverage) * (leverage > 0 ? down : up);
            long open = Math.max(1, Math.round(previous));
            long closeWon = i == history.size() - 1 ? startPriceWon : Math.max(1, Math.round(now));
            long high = Math.round(Math.max(open, closeWon) * (1 + wickUp));
            long low = Math.max(1, Math.round(Math.min(open, closeWon) * (1 - wickDown)));
            out.add(new PriceChart.Candle(c.startSecond(), open, Math.max(high, Math.max(open, closeWon)),
                    Math.min(low, Math.min(open, closeWon)), closeWon,
                    Math.round(Math.max(0, c.volume() * 0.6 + random.nextGaussian()))));
            previous = closeWon;
        }
        return out;
    }

    static Result generate(StockCatalog.Stock stock, RandomGenerator random) {
        var segments = stock.story();
        int total = segments.stream().mapToInt(StockCatalog.StorySegment::candles).sum();
        // 끝(시작가)에서 거꾸로 구간 시작 가격을 구한다.
        double[] startLog = new double[segments.size() + 1];
        startLog[segments.size()] = Math.log(stock.startPriceWon());
        for (int i = segments.size() - 1; i >= 0; i--) {
            startLog[i] = startLog[i + 1] - Math.log1p(segments.get(i).changePercent() / 100.0);
        }
        var candles = new ArrayList<PriceChart.Candle>();
        var notes = new ArrayList<Note>();
        long second = -(long) total * PriceChart.CANDLE_SECONDS;
        double previousClose = Math.exp(startLog[0]);
        for (int i = 0; i < segments.size(); i++) {
            var seg = segments.get(i);
            if (seg.note() != null && !seg.note().isBlank()) notes.add(new Note(second, seg.note()));
            int n = seg.candles();
            // 랜덤 걸음을 만든 뒤 끝이 0이 되도록 기울기를 빼서 양끝을 고정한다.
            double[] walk = new double[n + 1];
            for (int k = 1; k <= n; k++) walk[k] = walk[k - 1] + seg.volatility() * random.nextGaussian();
            for (int k = 1; k <= n; k++) {
                double t = (double) k / n;
                double log = startLog[i] + (startLog[i + 1] - startLog[i]) * t + walk[k] - t * walk[n];
                double close = Math.exp(log);
                double open = previousClose;
                double wick = seg.volatility() * 0.5;
                double high = Math.max(open, close) * (1 + Math.abs(random.nextGaussian()) * wick);
                double low = Math.min(open, close) * (1 - Math.abs(random.nextGaussian()) * wick);
                // 출근 전 거래량: 움직임이 클수록 많게, 잔잔하면 적게(다른 투자자들의 거래로 본다).
                double move = seg.volatility() == 0 ? 0 : Math.abs(Math.log(close / open)) / seg.volatility();
                long volume = Math.round(Math.max(0, 2 + 3 * move + random.nextGaussian()));
                long closeWon = k == n && i == segments.size() - 1 ? stock.startPriceWon() : Math.max(1, Math.round(close));
                candles.add(new PriceChart.Candle(second, Math.max(1, Math.round(open)),
                        Math.max(Math.round(high), Math.max(closeWon, Math.round(open))),
                        Math.max(1, Math.min(Math.round(low), Math.min(closeWon, Math.round(open)))),
                        closeWon, volume));
                previousClose = closeWon;
                second += PriceChart.CANDLE_SECONDS;
            }
        }
        return new Result(candles, notes);
    }
}
