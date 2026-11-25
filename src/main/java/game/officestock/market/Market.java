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
    /** 퇴근 전 마지막 2분은 흔들림이 커진다. */
    private static final int CLOSING_WINDOW_SECONDS = 120;
    static final int TICKS_PER_NEWS = NEWS_INTERVAL_SECONDS / TICK_SECONDS;
    static final int RUMOR_LEAD_TICKS = RUMOR_LEAD_SECONDS / TICK_SECONDS;
    /** 풀 가격이 목표가와의 차이를 1초에 좁히는 비율(2초에 20%와 같다). */
    private static final double CHASE_PER_TICK = 1 - Math.pow(Math.sqrt(0.8), TICK_SECONDS);
    private static final double RUMOR_CHANCE = 0.6;
    private static final double TRUE_RUMOR_CHANCE = 0.7;
    private static final double CLOSING_VOLATILITY = 2.5;

    /** @param kind "news"(속보) 또는 "rumor"(찌라시) @param symbol 관련 종목 코드 */
    public record NewsItem(int number, String kind, String symbol, String headline) {}

    private final RandomGenerator random;
    private final Map<String, StockPool> pools = new LinkedHashMap<>();
    private final Map<String, PriceDrift> drifts = new LinkedHashMap<>();
    private final List<NewsItem> history = new ArrayList<>();
    private final StockCatalog catalog;
    private final NewsCatalog news;
    private List<NewsCatalog.Card> deck;
    private String trueRumorSymbol;
    private final long closingTick;

    /**
     * 종목은 catalog, 뉴스와 찌라시 문구는 news에서 온다. 종목 코드(id)를 키로 쓴다.
     * @param sessionSeconds 출근부터 퇴근까지 길이. 마지막 2분이 장 마감 구간이다.
     */
    public Market(StockCatalog catalog, NewsCatalog news, RandomGenerator random, long sessionSeconds) {
        this.catalog = catalog;
        this.news = news;
        this.random = random;
        this.closingTick = Math.max(0, sessionSeconds - CLOSING_WINDOW_SECONDS) / TICK_SECONDS;
        for (var stock : catalog.stocks()) {
            pools.put(stock.id(), new StockPool(stock.poolShares(),
                    Math.multiplyExact(stock.poolShares(), stock.startPriceWon())));
            drifts.put(stock.id(), new PriceDrift(stock.startPriceWon(), stock.volatilityPerSecond()));
        }
        deck = MarketNews.shuffledDeck(news, random);
    }

    public StockCatalog catalog() {
        return catalog;
    }

    /** @return 종목 코드에 해당하는 풀, 없으면 null */
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
        double scale = tick >= closingTick ? CLOSING_VOLATILITY : 1;
        drifts.forEach((symbol, drift) -> pools.get(symbol).moveToward(drift.advance(scale, random), CHASE_PER_TICK));
    }

    private NewsCatalog.Card upcoming() {
        if (deck.isEmpty()) deck = MarketNews.shuffledDeck(news, random);
        return deck.getLast();
    }

    private void spreadRumor() {
        var next = upcoming();
        String symbol;
        int changePercent;
        if (random.nextDouble() < TRUE_RUMOR_CHANCE) {
            symbol = next.stock();
            changePercent = next.changePercent();
            trueRumorSymbol = symbol;
        } else {
            // 가짜: 다가오는 뉴스와 종목이나 방향 중 하나 이상이 다르다.
            // 세기는 그 종목의 뉴스 카드 중 하나를 골라 비슷하게 맞춘다.
            var stocks = catalog.stocks();
            do {
                var stock = stocks.get(random.nextInt(stocks.size()));
                var cards = news.cardsFor(stock.id());
                var sample = cards.get(random.nextInt(cards.size()));
                symbol = stock.id();
                changePercent = (random.nextBoolean() ? 1 : -1) * Math.abs(sample.changePercent());
            } while (symbol.equals(next.stock())
                    && Integer.signum(changePercent) == Integer.signum(next.changePercent()));
        }
        drifts.get(symbol).onRumor(changePercent, RUMOR_LEAD_TICKS);
        var phrases = changePercent > 0 ? news.rumors().good() : news.rumors().bad();
        var text = phrases.get(random.nextInt(phrases.size()))
                .replace(NewsCatalog.NAME, catalog.stock(symbol).name());
        history.add(new NewsItem(history.size() + 1, "rumor", symbol, text));
    }

    private void publishNews() {
        var card = upcoming();
        deck.removeLast();
        drifts.values().forEach(PriceDrift::clearRumor);
        boolean rumored = card.stock().equals(trueRumorSymbol);
        trueRumorSymbol = null;
        drifts.get(card.stock()).onNews(card.changePercent(), rumored, random);
        history.add(new NewsItem(history.size() + 1, "news", card.stock(),
                catalog.stock(card.stock()).name() + " · " + card.headline()));
    }
}
