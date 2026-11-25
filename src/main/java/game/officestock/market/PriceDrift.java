package game.officestock.market;

import java.util.random.RandomGenerator;

/**
 * 한 종목의 목표가. 뉴스가 정하는 적정가 위에 평소 흔들림, 뉴스·찌라시 충격, 플레이어 매매 흔적을 얹는다.
 * 모든 충격은 로그 단위로 더하므로 오를 때와 내릴 때가 대칭이다.
 */
final class PriceDrift {
    // 아래 비율은 초당 값을 틱 길이로 환산한다. 괄호 안은 2초 기준 값.
    /** 평소 흔들림이 남는 비율(2초에 85%). */
    private static final double NOISE_KEEP = perTick(0.922);
    /** 뉴스·찌라시 충격이 남는 비율(2초에 75%). */
    private static final double IMPULSE_DECAY = perTick(0.866);
    /** 플레이어 매매 흔적이 남는 비율(2초에 85%, 약 8초에 절반). */
    private static final double FLOW_DECAY = perTick(0.922);
    /** 찌라시가 진짜 뉴스 전까지 미리 반영하는 몫. */
    private static final double RUMOR_SHARE = 0.4;
    private static final double MIN_FAIR = 100, MAX_FAIR = 10_000;

    /** 틱당 표준편차. 생성자에는 초당 값을 받는다. */
    private final double volatility;
    private double fairWon;
    private double noise;
    private double impulse;
    private double rumorDrift;
    private double flow;

    PriceDrift(double fairWon, double volatility) {
        this.fairWon = fairWon;
        this.volatility = volatility * Math.sqrt(Market.TICK_SECONDS);
    }

    private static double perTick(double keptPerSecond) {
        return Math.pow(keptPerSecond, Market.TICK_SECONDS);
    }

    /** 한 틱 진행하고 이번 틱의 목표가를 돌려준다. */
    long advance(double volatilityScale, RandomGenerator random) {
        noise = noise * NOISE_KEEP + volatility * volatilityScale * random.nextGaussian();
        impulse = impulse * IMPULSE_DECAY + rumorDrift;
        flow *= FLOW_DECAY;
        double target = fairWon * Math.exp(noise + impulse + flow);
        return Math.clamp(Math.round(target), 50L, 20_000L);
    }

    /**
     * 진짜 뉴스. 적정가는 항상 뉴스 방향으로 움직이고, 지나가는 경로만 달라진다.
     * @return 이번 반응의 종류(로그·테스트용)
     */
    Reaction onNews(int changePercent, boolean rumoredBefore, RandomGenerator random) {
        double move = Math.log1p(changePercent / 100.0);
        fairWon = Math.clamp(fairWon * (1 + changePercent / 100.0), MIN_FAIR, MAX_FAIR);
        rumorDrift = 0;
        double roll = random.nextDouble();
        if (rumoredBefore && roll < 0.4) {
            impulse -= move * 0.6;
            return Reaction.PRICED_IN;
        }
        if (roll < 0.35) {
            impulse += move * (0.4 + 0.5 * random.nextDouble());
            return Reaction.OVERSHOOT;
        }
        return Reaction.DIRECT;
    }

    /** 찌라시: 뉴스 발표까지 남은 틱 동안 조금씩 미리 움직인다. 가짜면 발표 후 저절로 되돌아간다. */
    void onRumor(int changePercent, int ticksUntilNews) {
        double move = Math.log1p(changePercent / 100.0);
        // 충격이 틱마다 IMPULSE_DECAY만큼 줄어드는 것을 감안해 발표 시점에 약 RUMOR_SHARE만큼 쌓이게 한다.
        double accumulated = 0;
        for (int i = 0; i < ticksUntilNews; i++) accumulated = accumulated * IMPULSE_DECAY + 1;
        rumorDrift = move * RUMOR_SHARE / accumulated;
    }

    void clearRumor() {
        rumorDrift = 0;
    }

    /** 플레이어 주문이 풀 가격을 before에서 after로 옮겼다. 목표가도 그만큼 따라가 흔적이 잠시 남는다. */
    void onTrade(long beforeWon, long afterWon) {
        if (beforeWon > 0 && afterWon > 0) flow += Math.log((double) afterWon / beforeWon);
    }

    enum Reaction { DIRECT, OVERSHOOT, PRICED_IN }
}
