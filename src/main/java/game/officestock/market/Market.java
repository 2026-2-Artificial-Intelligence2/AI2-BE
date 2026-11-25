package game.officestock.market;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** 방 하나의 시장. 방의 잠금 안에서만 호출한다. */
public final class Market {
    /** 시세 갱신 간격. 아래 시간 상수와 PriceDrift의 비율은 모두 초 단위라 이 값만 바꾸면 된다. */
    public static final int TICK_SECONDS = 1;
    public static final long TICK_MILLIS = TICK_SECONDS * 1_000L;
    private static final int NEWS_INTERVAL_SECONDS = 20;
    /** 찌라시는 뉴스 몇 초 전에 도는가. */
    private static final int RUMOR_LEAD_SECONDS = 8;
    /** 약 15분 판에서 마지막 2분은 흔들림이 커진다. */
    private static final int CLOSING_SECONDS = 780;
    static final int TICKS_PER_NEWS = NEWS_INTERVAL_SECONDS / TICK_SECONDS;
    static final int RUMOR_LEAD_TICKS = RUMOR_LEAD_SECONDS / TICK_SECONDS;
    static final long CLOSING_TICK = CLOSING_SECONDS / TICK_SECONDS;
    /** 풀 가격이 목표가와의 차이를 1초에 좁히는 비율(2초에 20%와 같다). */
    private static final double CHASE_PER_TICK = 1 - Math.pow(Math.sqrt(0.8), TICK_SECONDS);
    private static final double RUMOR_CHANCE = 0.6;
    private static final double TRUE_RUMOR_CHANCE = 0.7;
    private static final double CLOSING_VOLATILITY = 2.5;

    public record NewsItem(int number, String kind, String headline) {}

    private static final List<String> GOOD_RUMORS = List.of(
            "%s 쪽에 좋은 소식이 곧 나온다는 얘기가 돈다",
            "%s 임원진이 오늘 유난히 웃고 다닌다는 목격담",
            "%s 담당 팀이 축하 회식을 잡았다는 소문");
    private static final List<String> BAD_RUMORS = List.of(
            "%s 임원들이 긴급 회의에 들어갔다는 소문",
            "%s 거래처와 마찰이 있다는 얘기가 돈다",
            "%s 담당자가 아침부터 전화를 안 받는다는 목격담");

    private final RandomGenerator random;
    private final Map<String, StockPool> pools = new LinkedHashMap<>();
    private final Map<String, PriceDrift> drifts = new LinkedHashMap<>();
    private final List<NewsItem> history = new ArrayList<>();
    private List<MarketNews> deck = MarketNews.shuffledDeck();
    private String trueRumorSymbol;

    public Market(RandomGenerator random) {
        this.random = random;
        // 초당 흔들림. 2초 간격일 때의 1.5%·2.5%·0.8%와 같은 세기다.
        add("민트식품", 0.0106);
        add("달나라로봇", 0.0177);
        add("우리회사", 0.0057);
    }

    private void add(String symbol, double volatility) {
        pools.put(symbol, new StockPool(1_000, 1_000_000));
        drifts.put(symbol, new PriceDrift(1_000, volatility));
    }

    public StockPool pool(String symbol) {
        return pools.get(symbol);
    }

    public Map<String, StockPool> pools() {
        return pools;
    }

    public List<NewsItem> history() {
        return history;
    }

    public long spotPrice(String symbol) {
        var reserves = pools.get(symbol).reserves();
        return reserves.cashWon() / reserves.shares();
    }

    /** 플레이어 주문 직후에 부른다. */
    public void recordTrade(String symbol, long spotBeforeWon) {
        drifts.get(symbol).onTrade(spotBeforeWon, spotPrice(symbol));
    }

    public void advance(long tick) {
        long phase = tick % TICKS_PER_NEWS;
        if (phase == TICKS_PER_NEWS - RUMOR_LEAD_TICKS && random.nextDouble() < RUMOR_CHANCE) {
            spreadRumor();
        }
        if (phase == 0) publishNews();
        double scale = tick >= CLOSING_TICK ? CLOSING_VOLATILITY : 1;
        drifts.forEach((symbol, drift) -> pools.get(symbol).moveToward(drift.advance(scale, random), CHASE_PER_TICK));
    }

    private MarketNews upcoming() {
        if (deck.isEmpty()) deck = MarketNews.shuffledDeck();
        return deck.getLast();
    }

    private void spreadRumor() {
        var next = upcoming();
        String symbol;
        int changePercent;
        if (random.nextDouble() < TRUE_RUMOR_CHANCE) {
            symbol = next.symbol();
            changePercent = next.changePercent();
            trueRumorSymbol = symbol;
        } else {
            // 가짜: 다가오는 뉴스와 종목이나 방향 중 하나 이상이 다르다.
            var symbols = List.copyOf(pools.keySet());
            do {
                symbol = symbols.get(random.nextInt(symbols.size()));
                changePercent = (random.nextBoolean() ? 1 : -1) * random.nextInt(5, 13);
            } while (symbol.equals(next.symbol())
                    && Integer.signum(changePercent) == Integer.signum(next.changePercent()));
        }
        drifts.get(symbol).onRumor(changePercent, RUMOR_LEAD_TICKS);
        var templates = changePercent > 0 ? GOOD_RUMORS : BAD_RUMORS;
        var text = templates.get(random.nextInt(templates.size())).formatted(symbol);
        history.add(new NewsItem(history.size() + 1, "rumor", text));
    }

    private void publishNews() {
        var news = upcoming();
        deck.removeLast();
        drifts.values().forEach(PriceDrift::clearRumor);
        boolean rumored = news.symbol().equals(trueRumorSymbol);
        trueRumorSymbol = null;
        drifts.get(news.symbol()).onNews(news.changePercent(), rumored, random);
        history.add(new NewsItem(history.size() + 1, "news",
                news.symbol() + " · " + news.headline()));
    }
}
