package game.officestock.market;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 솔로 게임의 보이지 않는 세력. 멀티 게임에서 남이 크게 사고팔며 생기는 기회와 위험을 혼자서도 느끼게 한다.
 * 한 종목을 십여 초에 걸쳐 몰래 사 모으고(거래량이 늘며 슬금슬금 오른다) 잠깐 버틴 뒤 몇 초 만에 다 판다(급락).
 * 열 번에 세 번은 거꾸로, 먼저 던져서 끌어내린 뒤 한꺼번에 되산다(급반등).
 * 매매는 실제로 풀을 거치므로 차트에 거래량이 찍히고, 충격도 플레이어 매매와 똑같이 적정가에 남았다가 되돌아간다.
 */
final class Whale {
    private enum Phase { WAIT, BUILD, HOLD, UNWIND }

    private static final int FIRST_MIN_TICKS = 40, FIRST_MAX_TICKS = 80;
    private static final int GAP_MIN_TICKS = 60, GAP_MAX_TICKS = 120;
    private static final int BUILD_MIN_TICKS = 15, BUILD_MAX_TICKS = 30;
    private static final int HOLD_MIN_TICKS = 3, HOLD_MAX_TICKS = 8;
    private static final int UNWIND_MIN_TICKS = 3, UNWIND_MAX_TICKS = 6;
    /** 사 모으는(던지는) 동안 가격을 움직이려는 폭. */
    private static final double MOVE_MIN = 0.08, MOVE_MAX = 0.18;
    private static final double PUMP_CHANCE = 0.7;
    /** 사 모으는 동안 이 확률로 한 틱 쉰다. 너무 고르면 티가 난다. */
    private static final double PAUSE_CHANCE = 0.3;
    /** 퇴근 이만큼 전에는 새로 들어오지 않는다(나갈 시간이 없다). */
    private static final int CLOSING_GUARD_TICKS = 45;

    private final long sessionTicks;
    private Phase phase = Phase.WAIT;
    private long nextStartTick;
    private String symbol;
    /** +1이면 사 모으는 세력, -1이면 던지는 세력. */
    private int direction;
    private long targetShares, heldShares;
    private int ticksLeft, totalTicks;

    Whale(long sessionTicks, RandomGenerator random) {
        this.sessionTicks = sessionTicks;
        this.nextStartTick = random.nextInt(FIRST_MIN_TICKS, FIRST_MAX_TICKS + 1);
    }

    /** 이번 틱에 세력이 할 매매. 양수면 매수, 음수면 매도 수량. 없으면 null. */
    record Order(String symbol, long quantity) {}

    /**
     * @param candidates 세력이 고를 수 있는 일반 종목(이번 틱 속보가 난 종목은 뺀다)
     * @param sharesOf 종목별 지금 풀의 주식 수
     */
    Order advance(long tick, List<String> candidates, java.util.function.ToLongFunction<String> sharesOf,
                  RandomGenerator random) {
        switch (phase) {
            case WAIT -> {
                if (tick < nextStartTick || candidates.isEmpty()) return null;
                if (tick > sessionTicks - CLOSING_GUARD_TICKS) return null;
                symbol = candidates.get(random.nextInt(candidates.size()));
                direction = random.nextDouble() < PUMP_CHANCE ? 1 : -1;
                double move = MOVE_MIN + random.nextDouble() * (MOVE_MAX - MOVE_MIN);
                long shares = sharesOf.applyAsLong(symbol);
                // 상수곱 풀에서 가격을 (1+move)배로 올리려면 S(1 - 1/√(1+move))주를 사야 한다.
                // 내리려면 S(1/√(1-move) - 1)주를 팔아야 한다.
                targetShares = Math.max(1, Math.round(direction > 0
                        ? shares * (1 - 1 / Math.sqrt(1 + move))
                        : shares * (1 / Math.sqrt(1 - move) - 1)));
                heldShares = 0;
                totalTicks = ticksLeft = random.nextInt(BUILD_MIN_TICKS, BUILD_MAX_TICKS + 1);
                phase = Phase.BUILD;
                return build(random);
            }
            case BUILD -> {
                return build(random);
            }
            case HOLD -> {
                if (--ticksLeft > 0) return null;
                totalTicks = ticksLeft = random.nextInt(UNWIND_MIN_TICKS, UNWIND_MAX_TICKS + 1);
                phase = Phase.UNWIND;
                return unwind();
            }
            case UNWIND -> {
                return unwind();
            }
        }
        return null;
    }

    private Order build(RandomGenerator random) {
        ticksLeft--;
        long remaining = targetShares - heldShares;
        if (ticksLeft <= 0 || remaining <= 0) {
            ticksLeft = random.nextInt(HOLD_MIN_TICKS, HOLD_MAX_TICKS + 1);
            phase = Phase.HOLD;
            return remaining > 0 ? take(remaining) : null;
        }
        if (random.nextDouble() < PAUSE_CHANCE) return null;
        long base = Math.max(1, targetShares / totalTicks);
        long quantity = Math.min(remaining, Math.max(1, Math.round(base * (0.5 + random.nextDouble() * 1.3))));
        return take(quantity);
    }

    private Order take(long quantity) {
        heldShares += quantity;
        return new Order(symbol, direction * quantity);
    }

    private Order unwind() {
        long quantity = ticksLeft <= 1 ? heldShares : Math.max(1, heldShares / ticksLeft);
        ticksLeft--;
        quantity = Math.min(quantity, heldShares);
        heldShares -= quantity;
        if (heldShares <= 0) {
            phase = Phase.WAIT;
            // 다음 세력은 이번이 끝난 뒤 1~2분 사이에 온다.
            nextStartTick = Long.MAX_VALUE;
        }
        return quantity > 0 ? new Order(symbol, -direction * quantity) : null;
    }

    /** 끝난 뒤 다음 시작 시각을 정한다. */
    void scheduleNext(long tick, RandomGenerator random) {
        if (phase == Phase.WAIT && nextStartTick == Long.MAX_VALUE)
            nextStartTick = tick + random.nextInt(GAP_MIN_TICKS, GAP_MAX_TICKS + 1);
    }
}
