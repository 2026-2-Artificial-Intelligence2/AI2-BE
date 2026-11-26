package game.officestock.market;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 종목 목록. 종목을 추가할 때는 resources/market/stocks.json에 항목을 하나 더하면 된다.
 * 뉴스는 news.json에 따로 있다(NewsCatalog). 서버가 시작할 때 한 번 읽고 검증하며,
 * 잘못된 항목이 있으면 서버가 뜨지 않는다.
 */
public record StockCatalog(List<Stock> stocks) {
    private static final Pattern ID = Pattern.compile("[A-Z0-9_]{1,16}");

    /**
     * @param id 주문·시세 메시지에 쓰는 고정 코드(영문 대문자·숫자·밑줄). 화면에 보이지 않는다.
     * @param name 화면과 뉴스에 보이는 이름
     * @param startPriceWon 시작 기준가. 적정가는 이 값의 1/10~10배 안에서 움직인다.
     * @param poolShares 풀의 주식 수. 클수록 큰 주문에도 가격이 덜 밀린다.
     * @param volatilityPerSecond 평소 흔들림의 초당 표준편차(0.01 = 1%)
     * @param story 출근 전 주가 흐름(선택). 구간을 순서대로 이어 붙여 차트의 과거 봉을 만들고, 마지막은 시작가에 닿는다.
     */
    public record Stock(String id, String name, long startPriceWon, long poolShares,
                        double volatilityPerSecond, List<StorySegment> story) {
        public Stock {
            story = story == null ? List.of() : List.copyOf(story);
        }

        public Stock(String id, String name, long startPriceWon, long poolShares, double volatilityPerSecond) {
            this(id, name, startPriceWon, poolShares, volatilityPerSecond, List.of());
        }
    }

    /**
     * 출근 전 흐름의 한 구간.
     * @param candles 이 구간의 10초 봉 개수
     * @param changePercent 구간 처음부터 끝까지의 변화율(%)
     * @param volatility 봉 하나의 흔들림(표준편차, 0.005 = 0.5%)
     * @param note 차트에 "지난 소식"으로 표시할 설명(선택)
     */
    public record StorySegment(int candles, double changePercent, double volatility, String note) {}

    /** 출근 전 흐름의 최대 길이(10초 봉 개수). 360개면 1시간. */
    public static final int MAX_STORY_CANDLES = 360;

    public StockCatalog {
        if (stocks == null || stocks.isEmpty()) throw invalid("종목이 하나 이상 필요합니다.");
        stocks = List.copyOf(stocks);
        var ids = new HashSet<String>();
        var names = new HashSet<String>();
        for (var s : stocks) {
            if (s.id() == null || !ID.matcher(s.id()).matches())
                throw invalid("종목 코드는 영문 대문자·숫자·밑줄 1~16자여야 합니다: " + s.id());
            if (!ids.add(s.id())) throw invalid("종목 코드가 겹칩니다: " + s.id());
            if (s.name() == null || s.name().isBlank()) throw invalid(s.id() + ": 이름이 비었습니다.");
            if (!names.add(s.name())) throw invalid("종목 이름이 겹칩니다: " + s.name());
            if (s.startPriceWon() < 10) throw invalid(s.id() + ": 시작 기준가는 10원 이상이어야 합니다.");
            if (s.poolShares() < 10) throw invalid(s.id() + ": 풀 주식 수는 10주 이상이어야 합니다.");
            Math.multiplyExact(s.startPriceWon(), s.poolShares());
            if (!(s.volatilityPerSecond() >= 0 && s.volatilityPerSecond() < 0.2))
                throw invalid(s.id() + ": 흔들림은 0 이상 0.2 미만이어야 합니다.");
            int storyCandles = 0;
            for (var seg : s.story()) {
                if (seg.candles() < 1) throw invalid(s.id() + ": story 구간의 candles는 1 이상이어야 합니다.");
                if (!(seg.changePercent() > -100)) throw invalid(s.id() + ": story 구간의 changePercent는 -100보다 커야 합니다.");
                if (!(seg.volatility() >= 0 && seg.volatility() < 0.1))
                    throw invalid(s.id() + ": story 구간의 volatility는 0 이상 0.1 미만이어야 합니다.");
                if (seg.note() != null && seg.note().length() > 40)
                    throw invalid(s.id() + ": story 구간의 note는 40자 이내여야 합니다.");
                storyCandles += seg.candles();
            }
            if (storyCandles > MAX_STORY_CANDLES)
                throw invalid(s.id() + ": story는 모두 합쳐 " + MAX_STORY_CANDLES + "봉(1시간)까지입니다.");
        }
    }

    public Stock stock(String id) {
        return stocks.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("stocks.json: " + message);
    }
}
