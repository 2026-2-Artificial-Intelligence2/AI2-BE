package game.officestock.room;

import game.officestock.market.Market;
import game.officestock.player.PlayerWallet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 부장님도 같은 시장에서 매매한다. 뉴스·찌라시가 나오면 판단해 사고팔고(그 매매도 가격을 움직인다),
 * 자기 손익으로 기분이 정해진다. 기분은 순찰 간격과 판정에 쓰인다(규칙은 서버가, 판단·대사는 LLM이 맡을 수 있다).
 *
 * LLM이 없거나 늦으면 규칙으로 판단한다: 헤드라인을 사람처럼 읽어 70%는 방향을 맞히고, 찌라시는 반쯤만 믿는다.
 * 물려서 크게 잃은 종목은 겁먹고 던진다(공포 손절).
 */
final class Boss {
    /** 부장님의 판돈. 플레이어(1천만 원)보다 커서 매매가 가격을 눈에 띄게 움직인다. */
    static final long STARTING_CASH_WON = 30_000_000;
    /** 이름 규칙상 플레이어가 쓸 수 없는 이름(ProfileService가 막는다). 거래 기록에 쓴다. */
    static final String LABEL = "부장님";
    /** 헤드라인을 읽고 방향을 맞힐 확률(규칙 판단). */
    static final double READ_ACCURACY = 0.7;
    /** 찌라시를 믿고 움직일 확률(규칙 판단). */
    static final double RUMOR_BELIEF = 0.5;
    /** 이만큼 물린 종목은 겁먹고 전부 던진다. */
    static final double PANIC_LOSS_PERCENT = 8;

    /** 기분. 부장님 손익(%)으로 정한다. 낮을수록 엄격하다. */
    enum Mood {
        FURIOUS(0.5, 70, 0.4, false),
        ANNOYED(0.75, 85, 0, false),
        NORMAL(1, Patrol.SUSPICION_MAX, 0, false),
        PLEASED(1.3, Patrol.SUSPICION_MAX, 0, false),
        ELATED(1.6, Patrol.SUSPICION_MAX, 0, true);

        /** 순찰 사이 쉬는 시간에 곱한다(작을수록 자주 돈다). */
        final double gapFactor;
        /** 업무 방치로 훈계하는 의심 기준(기본 100). */
        final double idleThreshold;
        /** 아무 잘못이 없어도 면담실로 데려갈 확률(점검할 때마다). */
        final double innocentCallChance;
        /** 걸려도 한 사람당 한 번은 봐준다. */
        final boolean forgivesOnce;

        Mood(double gapFactor, double idleThreshold, double innocentCallChance, boolean forgivesOnce) {
            this.gapFactor = gapFactor;
            this.idleThreshold = idleThreshold;
            this.innocentCallChance = innocentCallChance;
            this.forgivesOnce = forgivesOnce;
        }

        /** 화면에 보내는 이름. */
        String id() {
            return name().toLowerCase();
        }

        static Mood of(double profitPercent) {
            if (profitPercent <= -8) return FURIOUS;
            if (profitPercent <= -3) return ANNOYED;
            if (profitPercent >= 8) return ELATED;
            if (profitPercent >= 3) return PLEASED;
            return NORMAL;
        }
    }

    /**
     * 한 번의 매매 판단. buy면 현금의 fraction만큼 사고, 아니면 가진 주식의 fraction만큼 판다.
     * @param line 부장님 혼잣말(없으면 null)
     */
    record Decision(String symbol, boolean buy, double fraction, String line) {}

    /** 실제로 체결된 부장님 매매. */
    record Trade(String symbol, boolean buy, long quantity, long amountWon, long spotBeforeWon) {}

    final PlayerWallet wallet;
    private final RandomGenerator random;
    /** Claude 판단이 다른 스레드에서 도착하는 곳. 게임 스레드가 매 틱 꺼낸다. */
    final java.util.concurrent.ConcurrentLinkedQueue<Decision> incoming = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 판단을 내린 뒤 실제로 사고팔 틱(헤드라인을 읽는 데 몇 초 걸린다). */
    private final List<Scheduled> scheduled = new ArrayList<>();
    /** 이번 판에 한 번 봐준 사람(기분 좋을 때). */
    final java.util.Set<java.util.UUID> forgiven = new java.util.HashSet<>();
    /** 마지막 혼잣말과 그 틱. 화면이 말풍선으로 잠깐 보여 준다. */
    String line;
    long lineTick = -1;
    /** 마지막으로 판단한 뉴스 번호. */
    int seenNews;

    private record Scheduled(long tick, Decision decision) {}

    Boss(int feeBps, RandomGenerator random) {
        this.wallet = new PlayerWallet(STARTING_CASH_WON, feeBps);
        this.random = random;
    }

    /** 부장님 손익률(%): 현금 + 보유 주식 청산가 기준. */
    double profitPercent(Market market) {
        long value = wallet.balance().cashWon();
        for (var entry : wallet.holdings().entrySet()) {
            if (entry.getValue() > 0) value += wallet.liquidationValue(market.pool(entry.getKey()), entry.getValue());
        }
        return (value - STARTING_CASH_WON) * 100.0 / STARTING_CASH_WON;
    }

    Mood mood(Market market) {
        return Mood.of(profitPercent(market));
    }

    /**
     * 규칙 판단: 뉴스 한 건을 읽고 할 일. 할 게 없으면 빈 목록.
     * @param moves 그 뉴스가 실제로 말한 종목별 변동(%)
     */
    List<Decision> decide(Market.NewsItem item, Map<String, Integer> moves, Mood mood) {
        var decisions = new ArrayList<Decision>();
        boolean rumor = item.kind().equals("rumor");
        if (rumor && random.nextDouble() >= RUMOR_BELIEF) return decisions;
        for (var move : moves.entrySet()) {
            int sign = Integer.signum(move.getValue());
            if (sign == 0) continue;
            boolean readRight = random.nextDouble() < READ_ACCURACY;
            boolean bullish = readRight ? sign > 0 : sign < 0;
            // 기분이 나쁠수록 크게 지른다(만회하려고), 좋을수록 여유 있게 조금씩.
            double size = (rumor ? 0.1 : 0.2) * (mood == Mood.FURIOUS ? 1.6 : mood == Mood.ANNOYED ? 1.3 : 1)
                    * (0.7 + 0.6 * random.nextDouble());
            if (bullish) decisions.add(new Decision(move.getKey(), true, Math.min(size, 0.5), null));
            else if (wallet.sellable(move.getKey()) > 0) decisions.add(new Decision(move.getKey(), false, 1, null));
        }
        return decisions;
    }

    /** 판단을 몇 초 뒤(헤드라인을 읽는 시간)에 실행하도록 걸어 둔다. */
    void schedule(Decision decision, long tick) {
        scheduled.add(new Scheduled(tick + 2 + random.nextInt(4), decision));
    }

    /** 이미 시간을 들여 판단한 것(Claude 답): 1~2초 뒤에 실행한다. */
    void scheduleSoon(Decision decision, long tick) {
        scheduled.add(new Scheduled(tick + 1 + random.nextInt(2), decision));
    }

    /** 이번 틱에 실행할 판단을 꺼낸다. */
    List<Decision> due(long tick) {
        var due = new ArrayList<Decision>();
        scheduled.removeIf(s -> {
            if (s.tick() > tick) return false;
            due.add(s.decision());
            return true;
        });
        return due;
    }

    /** 크게 물린 종목은 겁먹고 던진다(공포 손절). 바닥 신호가 될 수도 있다. */
    List<Decision> panics(Market market) {
        var decisions = new ArrayList<Decision>();
        var portfolio = wallet.portfolio(market::pool);
        portfolio.positions().forEach((symbol, position) -> {
            if (position.shares() > 0 && position.profitPercent() <= -PANIC_LOSS_PERCENT && random.nextDouble() < 0.5)
                decisions.add(new Decision(symbol, false, 1, null));
        });
        return decisions;
    }

    /** 판단을 실제 주문으로 바꿔 체결한다. 살 돈·팔 주식이 없으면 null. */
    Trade execute(Decision decision, Market market) {
        var pool = market.pool(decision.symbol());
        if (pool == null) return null;
        long spot = market.spotPrice(decision.symbol());
        long quantity;
        if (decision.buy()) {
            long budget = (long) (wallet.balance().cashWon() * Math.max(0, Math.min(1, decision.fraction())));
            quantity = spot <= 0 ? 0 : budget / spot;
        } else {
            quantity = (long) Math.floor(wallet.sellable(decision.symbol()) * Math.max(0, Math.min(1, decision.fraction())));
        }
        if (quantity <= 0) return null;
        try {
            var fill = decision.buy() ? wallet.buy(decision.symbol(), pool, quantity) : wallet.sell(decision.symbol(), pool, quantity);
            return new Trade(decision.symbol(), decision.buy(), quantity, fill.netWon(decision.buy()), spot);
        } catch (RuntimeException e) {
            return null;   // 현금이 모자라는 등: 이번 판단은 건너뛴다.
        }
    }

    /** 대사를 남긴다(LLM 대사가 있으면 그것, 없으면 기분과 행동에 맞는 기본 대사). */
    void say(String text, long tick) {
        if (text == null || text.isBlank()) return;
        line = text.length() > 60 ? text.substring(0, 60) : text;
        lineTick = tick;
    }

    /** 기본 대사: 매매를 하고 난 뒤 기분에 따라. */
    String defaultLine(Trade trade, Mood mood) {
        var lines = switch (mood) {
            case FURIOUS -> trade.buy()
                    ? List.of("이번엔 진짜 오른다… 올라야 돼.", "물타기다, 물타기!", "다 복구한다 오늘.")
                    : List.of("에이 씨, 다 던져!", "이게 왜 떨어져!", "손절이다 손절…");
            case ANNOYED -> trade.buy()
                    ? List.of("흠… 이 정도면 사도 되겠지.", "조금만 담아 보자.")
                    : List.of("영 시원찮네.", "빼자 빼.");
            case NORMAL -> trade.buy()
                    ? List.of("오, 괜찮은데?", "이건 좀 사 둬야겠군.")
                    : List.of("여기까지만 먹자.", "정리할 때가 됐어.");
            case PLEASED, ELATED -> trade.buy()
                    ? List.of("오늘 감이 좋아~", "허허, 또 사야지.", "역시 난 촉이 있어.")
                    : List.of("이익 실현은 기본이지~", "허허, 많이 벌었다.");
        };
        return lines.get(random.nextInt(lines.size()));
    }
}
