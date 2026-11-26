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
    /** 뉴스 사이 간격(초). 매번 이 범위에서 무작위로 정해 다음 뉴스 시각을 외울 수 없게 한다. */
    private static final int NEWS_INTERVAL_MIN_SECONDS = 25, NEWS_INTERVAL_MAX_SECONDS = 40;
    /** 찌라시는 뉴스 몇 초 전에 도는가. */
    private static final int RUMOR_LEAD_SECONDS = 8;
    /** 퇴근 전 마지막 2분은 흔들림이 커진다. */
    private static final int CLOSING_WINDOW_SECONDS = 120;
    static final int RUMOR_LEAD_TICKS = RUMOR_LEAD_SECONDS / TICK_SECONDS;
    /** 풀 가격이 목표가와의 차이를 1초에 좁히는 비율(2초에 20%와 같다). */
    private static final double CHASE_PER_TICK = 1 - Math.pow(Math.sqrt(0.8), TICK_SECONDS);
    private static final double RUMOR_CHANCE = 0.6;
    private static final double TRUE_RUMOR_CHANCE = 0.7;
    private static final double CLOSING_VOLATILITY = 2.5;
    /**
     * 발표 때마다 종목별 실제 변화율을 적힌 값의 ±25% 안에서 흔든다. 같은 숫자를 적은 종목끼리도,
     * 같은 뉴스가 다시 나와도 움직임이 조금씩 다르다. 평균은 적힌 값 그대로다.
     */
    private static final double NEWS_JITTER = 0.25;
    /** 속보가 난 틱에 가격이 목표가와의 차이를 한 번에 좁히는 비율. */
    private static final double NEWS_GAP = 0.7;
    /** 레버리지 종목이 목표가를 따라가는 비율(틱당). 기초 종목을 바짝 따라가야 하므로 일반 종목보다 빠르다. */
    private static final double LEVERAGED_CHASE = 0.5;

    /**
     * @param kind "news"(속보) 또는 "rumor"(찌라시)
     * @param symbols 관련 종목 코드. 여러 종목 뉴스면 여러 개
     * @param body 본문. 찌라시나 본문 없는 뉴스는 null
     * @param second 발표된 판 경과 초. 차트에 표시한다.
     */
    public record NewsItem(int number, String kind, List<String> symbols, String headline, String body,
                           long second) {}

    /** 호가창의 한 칸. shares는 바로 앞 칸에서 이 가격까지 움직이려면 사고팔아야 하는 주식 수. */
    public record BookLevel(long priceWon, long shares) {}

    /**
     * 호가창. 이 시장에는 걸어 둔 주문이 없으므로, 풀 공식으로 "이 가격까지 가려면 몇 주"를 계산한다.
     * @param asks 매도 호가(현재가 위, 가까운 순) @param bids 매수 호가(현재가 이하, 가까운 순)
     */
    public record OrderBook(long priceWon, long stepWon, List<BookLevel> asks, List<BookLevel> bids) {}

    private static final int BOOK_LEVELS = 5;
    /** 호가 간격은 가격의 약 0.5%를 호가 단위에 맞춰 반올림한다. */
    private static final double BOOK_STEP_FRACTION = 0.005;

    private final RandomGenerator random;
    private final Map<String, StockPool> pools = new LinkedHashMap<>();
    private final Map<String, PriceDrift> drifts = new LinkedHashMap<>();
    /** 레버리지·인버스 종목(기초 종목을 따라간다). */
    private final Map<String, LeveragedPrice> leveraged = new LinkedHashMap<>();
    private final List<NewsItem> history = new ArrayList<>();
    private final StockCatalog catalog;
    private final NewsCatalog news;
    private List<NewsCatalog.Card> deck;
    private String trueRumorSymbol;
    private final long closingTick;
    private final PriceChart chart = new PriceChart();
    /** 출근 전 흐름의 "지난 소식"(차트 표시용). */
    private final List<Map<String, Object>> storyNotes = new ArrayList<>();
    /** 다음 뉴스가 나올 틱. */
    private long nextNewsTick;
    /** 솔로 게임의 세력. 멀티 게임에서는 null. */
    private final Whale whale;

    private long newsInterval() {
        return random.nextInt(NEWS_INTERVAL_MIN_SECONDS, NEWS_INTERVAL_MAX_SECONDS + 1) / TICK_SECONDS;
    }

    /** 지금 판 경과 초. 뉴스 시각과 봉 기록에 쓴다. */
    private long second;

    /**
     * 종목은 catalog, 뉴스와 찌라시 문구는 news에서 온다. 종목 코드(id)를 키로 쓴다.
     * @param sessionSeconds 출근부터 퇴근까지 길이. 마지막 2분이 장 마감 구간이다.
     */
    public Market(StockCatalog catalog, NewsCatalog news, RandomGenerator random, long sessionSeconds) {
        this(catalog, news, random, sessionSeconds, false);
    }

    /** @param whales 보이지 않는 세력을 넣는다(솔로 게임). */
    public Market(StockCatalog catalog, NewsCatalog news, RandomGenerator random, long sessionSeconds,
                  boolean whales) {
        this.whale = whales ? new Whale(sessionSeconds / TICK_SECONDS, random) : null;
        this.catalog = catalog;
        this.news = news;
        this.random = random;
        this.closingTick = Math.max(0, sessionSeconds - CLOSING_WINDOW_SECONDS) / TICK_SECONDS;
        for (var stock : catalog.stocks()) {
            pools.put(stock.id(), new StockPool(stock.poolShares(),
                    Math.multiplyExact(stock.poolShares(), stock.startPriceWon())));
            if (stock.derived()) {
                leveraged.put(stock.id(), new LeveragedPrice(stock.underlying(), stock.leverage(),
                        stock.startPriceWon(), catalog.stock(stock.underlying()).startPriceWon()));
            } else {
                drifts.put(stock.id(), new PriceDrift(stock.startPriceWon(), stock.volatilityPerSecond()));
            }
        }
        deck = MarketNews.shuffledDeck(news, random);
        nextNewsTick = newsInterval();
        for (var stock : catalog.stocks()) {
            if (stock.story().isEmpty()) continue;
            var story = PriceStory.generate(stock, random);
            chart.addHistory(stock.id(), story.candles());
            for (var note : story.notes()) {
                storyNotes.add(Map.of("second", note.second(), "kind", "past",
                        "symbols", List.of(stock.id()), "headline", note.text()));
            }
        }
        // 레버리지 종목의 출근 전 차트는 기초 종목의 봉을 배수로 따라 그리고, 끝을 시작가에 맞춘다.
        for (var stock : catalog.stocks()) {
            if (!stock.derived()) continue;
            var base = chart.all().get(stock.underlying());
            if (base == null || base.isEmpty()) continue;
            chart.addHistory(stock.id(), PriceStory.follow(base, stock.leverage(), stock.startPriceWon(), random));
        }
        for (var symbol : pools.keySet()) chart.record(symbol, 0, spotPrice(symbol), 0);
    }

    /** 레버리지 종목이면 기초 종목 코드, 아니면 null. */
    public String underlyingOf(String symbol) {
        var tracker = leveraged.get(symbol);
        return tracker == null ? null : tracker.underlying;
    }

    public StockCatalog catalog() {
        return catalog;
    }

    public PriceChart chart() {
        return chart;
    }

    /** 출근 전 흐름의 지난 소식. 차트에 "past" 종류로 표시한다. */
    public List<Map<String, Object>> storyNotes() {
        return storyNotes;
    }

    /** 실제 증권사 호가 단위(2023년 이후 코스피 기준)를 따른다. */
    static long tickUnit(long priceWon) {
        if (priceWon < 2_000) return 1;
        if (priceWon < 5_000) return 5;
        if (priceWon < 20_000) return 10;
        if (priceWon < 50_000) return 50;
        if (priceWon < 200_000) return 100;
        if (priceWon < 500_000) return 500;
        return 1_000;
    }

    public OrderBook orderBook(String symbol) {
        var r = pools.get(symbol).reserves();
        double shares = r.shares(), k = shares * r.cashWon();
        long spot = r.cashWon() / r.shares();
        long tick = tickUnit(spot);
        long step = tick * Math.max(1, Math.round(spot * BOOK_STEP_FRACTION / tick));
        long base = spot / step * step;
        // 현재가에 바싹 붙어 1주도 안 되는 칸은 건너뛰고, 그만큼 바깥 칸을 더 보여준다.
        var asks = new ArrayList<BookLevel>();
        long previous = 0;
        for (int i = 1; asks.size() < BOOK_LEVELS && i <= BOOK_LEVELS * 4; i++) {
            long price = base + i * step;
            // 풀에서 사서 가격을 price까지 올리면 풀에 남는 주식 수 = sqrt(k / price)
            long cumulative = (long) Math.floor(shares - Math.sqrt(k / price));
            if (asks.isEmpty() && cumulative <= 0) continue;
            asks.add(new BookLevel(price, Math.max(0, cumulative - previous)));
            previous = Math.max(previous, cumulative);
        }
        var bids = new ArrayList<BookLevel>();
        previous = 0;
        for (int i = 0; bids.size() < BOOK_LEVELS && i < BOOK_LEVELS * 4; i++) {
            long price = base - i * step;
            if (price <= 0) break;
            long cumulative = (long) Math.floor(Math.sqrt(k / price) - shares);
            if (bids.isEmpty() && cumulative <= 0) continue;
            bids.add(new BookLevel(price, Math.max(0, cumulative - previous)));
            previous = Math.max(previous, cumulative);
        }
        return new OrderBook(spot, step, asks, bids);
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

    /**
     * 플레이어가 메신저에서 뿌린 익명 찌라시. 시스템 찌라시와 똑같이 보이지만 가격은 움직이지 않는다.
     * @return 알림창에 들어간 항목
     */
    public NewsItem addPlayerRumor(String headline, String body) {
        var item = new NewsItem(history.size() + 1, "rumor", List.of(), headline, body, second);
        history.add(item);
        return item;
    }

    /** 플레이어 주문 직후에 부른다. */
    public void recordTrade(String symbol, long spotBeforeWon, long quantity) {
        if (leveraged.containsKey(symbol)) leveraged.get(symbol).onTrade(spotBeforeWon, spotPrice(symbol));
        else drifts.get(symbol).onTrade(spotBeforeWon, spotPrice(symbol));
        chart.record(symbol, second, spotPrice(symbol), quantity);
    }

    public void advance(long tick) {
        second = tick * TICK_SECONDS;
        if (tick == nextNewsTick - RUMOR_LEAD_TICKS && random.nextDouble() < RUMOR_CHANCE) {
            spreadRumor();
        }
        newsThisTick.clear();
        if (tick == nextNewsTick) {
            publishNews();
            nextNewsTick += newsInterval();
        }
        if (whale != null) moveWhale(tick);
        double scale = tick >= closingTick ? CLOSING_VOLATILITY : 1;
        // 속보가 난 종목은 그 순간 남은 차이의 NEWS_GAP만큼 바로 움직인다. 천천히 따라가면 먼저 사는 쪽이 거저 먹는다.
        drifts.forEach((symbol, drift) -> pools.get(symbol).moveToward(drift.advance(scale, random),
                newsThisTick.contains(symbol) ? NEWS_GAP : CHASE_PER_TICK));
        // 레버리지 종목은 기초 종목이 움직인 뒤에 그 움직임을 배수로 따라간다(속보 갭도 같이).
        leveraged.forEach((symbol, tracker) -> pools.get(symbol).moveToward(
                tracker.advance(spotPrice(tracker.underlying)),
                newsThisTick.contains(tracker.underlying) ? Math.max(NEWS_GAP, LEVERAGED_CHASE) : LEVERAGED_CHASE));
        for (var symbol : pools.keySet()) chart.record(symbol, second, spotPrice(symbol), 0);
    }

    /** 세력의 이번 틱 매매를 실제 풀에 넣는다. 플레이어 매매처럼 차트 거래량과 적정가에 반영된다. */
    private void moveWhale(long tick) {
        var candidates = drifts.keySet().stream().filter(symbol -> !newsThisTick.contains(symbol)).toList();
        var order = whale.advance(tick, candidates, symbol -> pools.get(symbol).reserves().shares(), random);
        whale.scheduleNext(tick, random);
        if (order == null) return;
        var pool = pools.get(order.symbol());
        long before = spotPrice(order.symbol());
        long quantity = Math.abs(order.quantity());
        try {
            if (order.quantity() > 0) pool.buy(quantity, Long.MAX_VALUE);
            else pool.sell(quantity, quantity);
        } catch (IllegalArgumentException e) {
            return;
        }
        recordTrade(order.symbol(), before, quantity);
    }

    private NewsCatalog.Card upcoming() {
        if (deck.isEmpty()) deck = MarketNews.shuffledDeck(news, random);
        return deck.getLast();
    }

    private void spreadRumor() {
        var next = upcoming();
        String symbol;
        int changePercent;
        Integer upcomingChange;
        if (random.nextDouble() < TRUE_RUMOR_CHANCE) {
            // 진짜: 다가오는 뉴스에서 가장 크게 움직일 종목을 가리킨다.
            var biggest = next.moves().entrySet().stream()
                    .max((a, b) -> Integer.compare(Math.abs(a.getValue()), Math.abs(b.getValue()))).orElseThrow();
            symbol = biggest.getKey();
            changePercent = biggest.getValue();
            trueRumorSymbol = symbol;
        } else {
            // 가짜: 다가오는 뉴스와 종목이나 방향 중 하나 이상이 다르다.
            // 세기는 그 종목의 뉴스 카드 중 하나를 골라 비슷하게 맞춘다.
            var stocks = catalog.stocks().stream().filter(st -> !st.derived()).toList();
            do {
                var stock = stocks.get(random.nextInt(stocks.size()));
                var cards = news.cardsFor(stock.id());
                var sample = cards.get(random.nextInt(cards.size()));
                symbol = stock.id();
                changePercent = (random.nextBoolean() ? 1 : -1) * Math.abs(sample.moves().get(symbol));
                upcomingChange = next.moves().get(symbol);
            } while (upcomingChange != null
                    && Integer.signum(changePercent) == Integer.signum(upcomingChange));
        }
        drifts.get(symbol).onRumor(changePercent, RUMOR_LEAD_TICKS);
        var phrases = changePercent > 0 ? news.rumors().good() : news.rumors().bad();
        var text = phrases.get(random.nextInt(phrases.size()))
                .replace(NewsCatalog.NAME, catalog.stock(symbol).name());
        history.add(new NewsItem(history.size() + 1, "rumor", List.of(symbol), text, null, second));
    }

    /** 이번 틱에 속보가 난 종목. 그 틱에는 가격이 목표가로 크게 한 번에 움직인다(갭). */
    private final java.util.Set<String> newsThisTick = new java.util.HashSet<>();

    private void publishNews() {
        var card = upcoming();
        deck.removeLast();
        drifts.values().forEach(PriceDrift::clearRumor);
        // 종목마다 따로 반응한다: 실제 폭(±25%)도, 과잉 반응·선반영 같은 경로도 종목별로 정해진다.
        card.moves().forEach((id, change) -> {
            newsThisTick.add(id);
            double jittered = change * (1 + NEWS_JITTER * (2 * random.nextDouble() - 1));
            drifts.get(id).onNews(Math.max(jittered, -95), id.equals(trueRumorSymbol), random);
        });
        trueRumorSymbol = null;
        var headline = card.multiStock() ? card.headline()
                : catalog.stock(card.stock()).name() + " · " + card.headline();
        history.add(new NewsItem(history.size() + 1, "news",
                List.copyOf(card.moves().keySet()), headline, card.body(), second));
    }
}
