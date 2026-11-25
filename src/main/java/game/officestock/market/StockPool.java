package game.officestock.market;

import java.math.BigInteger;

public final class StockPool {
    private long shares;
    private long cashWon;

    public StockPool(long shares, long cashWon) {
        if (shares <= 0 || cashWon <= 0) {
            throw new IllegalArgumentException("풀의 주식과 현금은 양수여야 합니다.");
        }
        this.shares = shares;
        this.cashWon = cashWon;
    }

    public record Reserves(long shares, long cashWon) {}

    public Reserves reserves() {
        return new Reserves(shares, cashWon);
    }

    /** 풀 가격을 목표가 쪽으로 차이의 fraction만큼 옮긴다. */
    public void moveToward(long targetPriceWon, double fraction) {
        if (targetPriceWon <= 0) throw new IllegalArgumentException("목표가는 양수여야 합니다.");
        long targetCash = Math.multiplyExact(shares, targetPriceWon);
        long gap = targetCash - cashWon;
        // 차이가 작아 이동량이 0원이 되어도 목표에 도달할 수 있게 마지막 구간은 1원씩 이동한다.
        long step = (long) (gap * fraction);
        cashWon += step == 0 ? Long.signum(gap) : step;
    }

    public long buy(long quantity, long availableWon) {
        if (quantity <= 0 || quantity >= shares) {
            throw new IllegalArgumentException("매수할 수 없는 수량입니다.");
        }
        long nextShares = shares - quantity;
        long nextCash = cashRequiredFor(nextShares);
        long cost = nextCash - cashWon;
        if (cost > availableWon) {
            throw new IllegalArgumentException("매수할 현금이 부족합니다.");
        }
        shares = nextShares;
        cashWon = nextCash;
        return cost;
    }

    public long sell(long quantity, long ownedShares) {
        return sell(quantity, ownedShares, Long.MAX_VALUE);
    }

    public long sell(long quantity, long ownedShares, long maxProceeds) {
        if (quantity <= 0 || quantity > ownedShares) {
            throw new IllegalArgumentException("매도할 수 없는 수량입니다.");
        }
        long nextShares = Math.addExact(shares, quantity);
        long nextCash = cashRequiredFor(nextShares);
        long proceeds = cashWon - nextCash;
        if (proceeds == 0) {
            throw new IllegalArgumentException("매도 대금이 1원 미만입니다.");
        }
        if (proceeds > maxProceeds) {
            throw new IllegalArgumentException("지갑의 현금 한도를 초과합니다.");
        }
        shares = nextShares;
        cashWon = nextCash;
        return proceeds;
    }

    /** 풀을 바꾸지 않고, 지금 quantity주를 팔면 받을 금액을 계산한다. 퇴근 정산의 주식 평가에 쓴다. */
    public long quoteSell(long quantity) {
        if (quantity <= 0) return 0;
        return cashWon - cashRequiredFor(Math.addExact(shares, quantity));
    }

    private long cashRequiredFor(long nextShares) {
        var product = BigInteger.valueOf(shares).multiply(BigInteger.valueOf(cashWon));
        var divisor = BigInteger.valueOf(nextShares);
        // 풀에 남는 현금을 올림하면 매수 대금은 올림, 매도 대금은 내림된다.
        return product.add(divisor).subtract(BigInteger.ONE).divide(divisor).longValueExact();
    }
}
