package game.officestock.market;

/**
 * 레버리지·인버스 종목의 목표가. 기초 종목 가격이 이번 틱에 r만큼 움직이면 목표가는 (1 + 배수 × r)배가 된다.
 * 매일이 아니라 매초 다시 맞추는 방식이라, 오르내림이 반복되면 배수만큼 정확히 따라가지 않고 조금씩 깎인다(실제 레버리지 상품과 같다).
 * 이 종목 자체의 매매 충격은 일반 종목과 같게 대부분 남고 일부만 되돌아간다.
 */
final class LeveragedPrice {
    private static final double FLOW_DECAY = Math.pow(0.922, Market.TICK_SECONDS);

    final String underlying;
    final double leverage;
    private final double minTarget, maxTarget;
    private double target;
    private double flow;
    private double previousUnderlying;

    LeveragedPrice(String underlying, double leverage, long startPriceWon, long underlyingStartWon) {
        this.underlying = underlying;
        this.leverage = leverage;
        this.target = startPriceWon;
        this.minTarget = Math.max(1, startPriceWon / 50.0);
        this.maxTarget = startPriceWon * 50.0;
        this.previousUnderlying = underlyingStartWon;
    }

    /** 기초 종목의 지금 가격을 받아 한 틱 진행하고 목표가를 돌려준다. */
    long advance(long underlyingSpotWon) {
        double r = underlyingSpotWon / previousUnderlying - 1;
        previousUnderlying = underlyingSpotWon;
        // 한 틱에 기초 종목이 1/배수 넘게 반대로 움직여도 0 아래로 가지 않게 바닥을 둔다.
        target = Math.clamp(target * Math.max(0.05, 1 + leverage * r), minTarget, maxTarget);
        flow *= FLOW_DECAY;
        return Math.round(target * Math.exp(flow));
    }

    void onTrade(long beforeWon, long afterWon) {
        if (beforeWon <= 0 || afterWon <= 0) return;
        double move = Math.log((double) afterWon / beforeWon);
        target = Math.clamp(target * Math.exp(move * PriceDrift.PERMANENT_IMPACT), minTarget, maxTarget);
        flow += move * (1 - PriceDrift.PERMANENT_IMPACT);
    }
}
