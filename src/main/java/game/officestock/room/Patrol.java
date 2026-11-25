package game.officestock.room;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 부장님 순찰. 2초 박자로 통로를 한 칸씩 걷고, 목표 자리 뒤에 도착하면 한 박자 뒤 화면을 본다.
 *
 * 의심 게이지(0~100): 주식 창을 보는 것 자체는 게임의 기본이라 벌하지 않는다. 마지막 업무 제출 뒤
 * 오래 지나면 오르고, 업무를 끝내면 내려간다. 게이지가 높을수록 순찰 대상으로 뽑히기 쉽고, 100이면
 * 부장님이 곧장 온다. 100인 채로 점검받으면 업무 창을 띄워 놔도 훈계를 받는다.
 */
final class Patrol {
    enum View { WORK, MARKET, MEETING }

    record Point(double x, double z) {}
    record Listener(Point position, int forwardZ) {}
    record Step(Point from, Point to, Listener listener) {}

    static final int BEAT_SECONDS = 2;
    /** 마지막 업무 뒤 이 시간까지는 의심이 오르지 않는다. 업무를 자주 하지 않아도 되게 하는 여유. */
    static final int SUSPICION_GRACE_SECONDS = 45;
    /** 이 시간까지는 초당 +1, 넘으면 초당 +2. */
    static final int SUSPICION_FAST_SECONDS = 90;
    static final double SUSPICION_PER_TASK = 35;
    static final double SUSPICION_MAX = 100;
    /** 이 값을 넘는 순간 부장님 헛기침이 그 사람에게만 들린다. */
    static final double SUSPICION_COUGH = 70;
    /** 훈계를 받고 나면 의심이 이 값으로 내려간다. */
    static final double SUSPICION_AFTER_MEETING = 30;
    /** 순찰 대상 가중치 = 1 + 의심/이 값. 0이면 1, 100이면 6. */
    static final double SUSPICION_WEIGHT_DIVISOR = 20;
    static final int MARKET_MEETING_BEATS = 5;   // 주식 창 적발: 약 10초
    static final int IDLE_MEETING_BEATS = 3;     // 업무 방치 훈계: 약 6초

    // Perimeter aisle, in metres. Seat rows face each other across the desks.
    private static final List<Point> AISLE = List.of(
            new Point(-3, -2), new Point(-1, -2), new Point(1, -2), new Point(3, -2),
            new Point(3, 0), new Point(3, 2), new Point(1, 2), new Point(-1, 2),
            new Point(-3, 2), new Point(-3, 0));
    private static final List<Integer> STOPS = List.of(1, 2, 7, 6);

    static final class Seat {
        final int stop;
        final Listener listener;
        View view = View.WORK;
        long returnTick;
        /** 훈계 이유: "market"(주식 창 적발) 또는 "idle"(업무 방치). */
        String meetingReason;
        double suspicion;
        long idleSeconds;
        /** 연결돼 있는가. 끊긴 동안은 의심이 오르지 않고, 업무 방치로 혼나지도 않는다. */
        boolean present = true;
        /** 헛기침이 난 박자. 그 박자의 시세 메시지에 한 번 실어 보낸다. */
        long coughBeat = -1;

        Seat(int stop) {
            this.stop = stop;
            Point behind = AISLE.get(stop);
            int forwardZ = behind.z() < 0 ? 1 : -1;
            listener = new Listener(new Point(behind.x(), behind.z() + forwardZ), forwardZ);
        }
    }

    final LinkedHashMap<UUID, Seat> seats = new LinkedHashMap<>();
    private UUID target;
    private long nextWalk = 7;
    private int waypoint;
    private int previous;
    private int destination;
    private int direction;
    boolean walking;
    /** 마지막으로 진행한 순찰 박자. 클라이언트가 같은 박자의 발소리를 두 번 재생하지 않게 한다. */
    long beat;

    void start(List<UUID> players) {
        if (!seats.isEmpty()) return;
        for (int i = 0; i < players.size(); i++) {
            seats.put(players.get(i), new Seat(STOPS.get(i)));
        }
    }

    /** 업무 한 건을 끝냈다. */
    void onWorkDone(UUID player) {
        var seat = seats.get(player);
        if (seat == null) return;
        seat.idleSeconds = 0;
        seat.suspicion = Math.max(0, seat.suspicion - SUSPICION_PER_TASK);
    }

    void advance(long tick) {
        beat = tick;
        for (Seat s : seats.values()) {
            if (s.view == View.MEETING && tick >= s.returnTick) s.view = View.WORK;
            if (s.present && s.view != View.MEETING) raiseSuspicion(s, tick);
        }
        walking = false;
        if (target == null && !seats.isEmpty()) {
            var summoned = summoned();
            if (summoned != null || tick >= nextWalk) {
                target = summoned != null ? summoned : weightedPick();
                destination = seats.get(target).stop;
                direction = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                if (Math.floorMod(waypoint + direction, AISLE.size()) == destination) {
                    direction = -direction;
                }
                // Starting at the same stop means walking one full lap before inspecting.
            }
        } else if (target != null && waypoint == destination) {
            inspect(tick);
            return;
        }
        if (target != null) {
            previous = waypoint;
            waypoint = Math.floorMod(waypoint + direction, AISLE.size());
            walking = true;
        }
    }

    private void raiseSuspicion(Seat s, long tick) {
        double before = s.suspicion;
        for (int i = 0; i < BEAT_SECONDS; i++) {
            s.idleSeconds++;
            if (s.idleSeconds > SUSPICION_FAST_SECONDS) s.suspicion += 2;
            else if (s.idleSeconds > SUSPICION_GRACE_SECONDS) s.suspicion += 1;
        }
        s.suspicion = Math.min(SUSPICION_MAX, s.suspicion);
        if (before < SUSPICION_COUGH && s.suspicion >= SUSPICION_COUGH) s.coughBeat = tick;
    }

    /** 의심이 가득 찬 사람이 있으면 부장님이 순서를 기다리지 않고 곧장 간다(가장 높은 사람). */
    private UUID summoned() {
        UUID best = null;
        double bestSuspicion = SUSPICION_MAX;
        for (var e : seats.entrySet()) {
            var s = e.getValue();
            if (s.present && s.view != View.MEETING && s.suspicion >= bestSuspicion) {
                best = e.getKey();
                bestSuspicion = s.suspicion;
            }
        }
        return best;
    }

    /** 의심이 높을수록 뽑히기 쉽다. */
    private UUID weightedPick() {
        double total = 0;
        for (var s : seats.values()) total += 1 + s.suspicion / SUSPICION_WEIGHT_DIVISOR;
        double roll = ThreadLocalRandom.current().nextDouble(total);
        for (var e : seats.entrySet()) {
            roll -= 1 + e.getValue().suspicion / SUSPICION_WEIGHT_DIVISOR;
            if (roll < 0) return e.getKey();
        }
        return seats.lastEntry().getKey();
    }

    private void inspect(long tick) {
        Seat seat = seats.get(target);
        if (seat != null && seat.view == View.MARKET) {
            meeting(seat, tick, MARKET_MEETING_BEATS, "market");
        } else if (seat != null && seat.present && seat.view == View.WORK
                && seat.suspicion >= SUSPICION_MAX) {
            meeting(seat, tick, IDLE_MEETING_BEATS, "idle");
        }
        target = null;
        nextWalk = tick + ThreadLocalRandom.current().nextInt(6, 12);
    }

    private static void meeting(Seat seat, long tick, int beats, String reason) {
        seat.view = View.MEETING;
        seat.returnTick = tick + beats;
        seat.meetingReason = reason;
        seat.suspicion = SUSPICION_AFTER_MEETING;
        seat.idleSeconds = 0;
    }

    Step step(Seat seat) {
        return new Step(AISLE.get(previous), AISLE.get(waypoint), seat.listener);
    }
}
