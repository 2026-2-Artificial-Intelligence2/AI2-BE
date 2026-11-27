package game.officestock.room;

import java.util.ArrayList;
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
 *
 * 부장님 자리: 사무실 동쪽에 책상이 있다. 순찰과 순찰 사이에는 자리에 앉아 매매하고(발소리 없음),
 * 점검이 끝나면 자리로 걸어 돌아간다. 기분(부장님 손익)에 따라 순찰 간격·판정이 달라진다({@link Boss.Mood}).
 *
 * 소리: 마이크 소리가 50 이상(소리 막대 절반)으로 1초 이어지면 부장님이 그 사람에게 걸어간다. 떠든 것만으로 훈계하지는
 * 않고, 도착해서 평소처럼 화면을 본다(주식·메신저 창이면 적발).
 */
final class Patrol {
    enum View {
        WORK, MARKET, MESSENGER, MEETING;

        /** 부장님에게 들키면 훈계받는 화면(주식 창, 메신저 창). */
        boolean slacking() {
            return this == MARKET || this == MESSENGER;
        }
    }

    record Point(double x, double z) {}
    record Listener(Point position, int forwardZ) {}
    record Step(Point from, Point to, Listener listener) {}

    static final int BEAT_SECONDS = 2;
    // 기준: 40초에 한 건이면 의심이 제자리다(20초 유예 + 20초 동안 +1 = +20, 업무 1건 = -20).
    // 그보다 뜸하면 쌓이고, 아무것도 안 하면 80초 만에 100이 된다.
    /** 마지막 업무 뒤 이 시간까지는 의심이 오르지 않는다. */
    static final int SUSPICION_GRACE_SECONDS = 20;
    /** 이 시간까지는 초당 +1, 넘으면 초당 +2. */
    static final int SUSPICION_FAST_SECONDS = 40;
    static final double SUSPICION_PER_TASK = 20;
    static final double SUSPICION_MAX = 100;
    /** 이 값을 넘는 순간 부장님 헛기침이 그 사람에게만 들린다. */
    static final double SUSPICION_COUGH = 70;
    /** 훈계를 받고 나면 의심이 이 값으로 내려간다. */
    static final double SUSPICION_AFTER_MEETING = 30;
    /** 순찰 대상 가중치 = 1 + 의심/이 값. 0이면 1, 100이면 6. */
    static final double SUSPICION_WEIGHT_DIVISOR = 20;
    static final int MARKET_MEETING_BEATS = 5;   // 주식 창 적발: 약 10초
    static final int IDLE_MEETING_BEATS = 3;     // 업무 방치 훈계: 약 6초
    /** 이 크기(0~100) 이상이면 크게 떠드는 것이다. */
    static final int LOUD_LEVEL = 50;
    /** 크게 떠드는 게 이만큼 이어지면 부장님이 듣고 온다. */
    static final long LOUD_MILLIS = 1_000;
    /** 말 사이 짧게 조용해지는 건 이어진 것으로 본다. 이보다 길게 조용하면 처음부터 다시 잰다. */
    static final long LOUD_GAP_MILLIS = 500;

    // Perimeter aisle, in metres. Seat rows face each other across the desks.
    private static final List<Point> AISLE = List.of(
            new Point(-3, -2), new Point(-1, -2), new Point(1, -2), new Point(3, -2),
            new Point(3, 0), new Point(3, 2), new Point(1, 2), new Point(-1, 2),
            new Point(-3, 2), new Point(-3, 0));
    private static final List<Integer> STOPS = List.of(1, 2, 7, 6);
    /** 부장님 책상(의자) 자리와, 통로에서 책상으로 드나드는 지점(동쪽 가운데). */
    static final Point DESK = new Point(6.3, 0);
    private static final int DESK_ENTRY = 4;

    static final class Seat {
        final int stop;
        final Listener listener;
        View view = View.WORK;
        long returnTick;
        /** 훈계 이유: "market"(주식 창 적발), "messenger"(메신저 창 적발), "idle"(업무 방치). */
        String meetingReason;
        /** 이번 판에 훈계받은 횟수(적발·업무 방치 모두). 퇴근 화면의 "최다 훈계" 상에 쓴다. */
        int meetings;
        double suspicion;
        long idleSeconds;
        /** 연결돼 있는가. 끊긴 동안은 의심이 오르지 않고, 업무 방치로 혼나지도 않는다. */
        boolean present = true;
        /** 헛기침이 난 박자. 그 박자의 시세 메시지에 한 번 실어 보낸다. */
        long coughBeat = -1;
        /** 크게 떠들기 시작한 시각(ms). -1이면 조용하다. */
        long loudSinceMillis = -1;
        /** 크게 떠들다가 조용해진 시각(ms). -1이면 아직 떠드는 중이거나 조용하다. */
        long quietSinceMillis = -1;

        Seat(int stop) {
            this.stop = stop;
            Point behind = AISLE.get(stop);
            int forwardZ = behind.z() < 0 ? 1 : -1;
            listener = new Listener(new Point(behind.x(), behind.z() + forwardZ), forwardZ);
        }
    }

    final LinkedHashMap<UUID, Seat> seats = new LinkedHashMap<>();
    private UUID target;
    /** 크게 떠들어서 부장님이 들은 사람. 지금 다른 사람에게 가는 중이면 그 다음에 간다. */
    private UUID loudCall;
    /** 방금 훈계로 불려 간 사람들. 방이 메신저에 알리고 비운다. */
    final List<UUID> justCalled = new ArrayList<>();
    /** 방금 기분이 좋아 한 번 봐준 사람들. 방이 그 사람에게 알리고 비운다. */
    final List<UUID> justForgiven = new ArrayList<>();
    /** 이번 판에 한 번 봐준 사람(기분이 아주 좋을 때 한 사람당 한 번). */
    private final java.util.Set<UUID> forgiven = new java.util.HashSet<>();
    /**
     * 순찰과 순찰 사이 쉬는 박자(2초 단위). 15~24박자 = 30~48초(부장님도 자리에서 매매하므로 예전 20~38초보다 길다).
     * 기분에 따라 곱한다. 의심 100·떠든 사람 호출은 이 간격을 기다리지 않는다.
     */
    static final int PATROL_GAP_MIN_BEATS = 15, PATROL_GAP_MAX_BEATS = 25;
    private long nextWalk = PATROL_GAP_MIN_BEATS;
    private int waypoint = DESK_ENTRY;
    private int destination;
    private int direction;
    /** 이번 박자 발걸음의 시작·끝 지점. */
    private Point from = DESK, to = DESK;
    boolean walking;
    /** 자리에 앉아 있는가(순찰 사이, 점심 뒤 복귀). */
    boolean atDesk = true;
    /** 점검을 마치고 자리로 돌아가는 중인가. */
    private boolean returning;
    /** 부장님 기분. 방이 매 박자 부장님 손익으로 정해 준다. */
    Boss.Mood mood = Boss.Mood.NORMAL;
    /** 점심시간 등으로 부장님이 자리를 비웠는가. 비운 동안은 순찰·점검이 없고 의심도 오르지 않는다. */
    private boolean away;
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

    /**
     * 부장님이 자리를 비우거나 돌아온다. 비우는 순간 하던 순찰을 그만두고, 돌아오면 모두의 업무 유예를
     * 처음부터 다시 주고(비운 동안 업무를 못 했으므로) 곧 순찰을 시작한다.
     */
    void setAway(boolean nowAway, long tick) {
        if (away == nowAway) return;
        away = nowAway;
        if (away) {
            target = null;
            loudCall = null;
            walking = false;
            returning = false;
        } else {
            // 점심에서 돌아오면 자리에 앉아 있다가 곧 순찰을 나선다.
            for (var s : seats.values()) s.idleSeconds = 0;
            atDesk = true;
            waypoint = DESK_ENTRY;
            from = to = DESK;
            nextWalk = tick + ThreadLocalRandom.current().nextInt(1, 3);
        }
    }

    void advance(long tick) {
        beat = tick;
        for (Seat s : seats.values()) {
            if (s.view == View.MEETING && tick >= s.returnTick) s.view = View.WORK;
            if (!away && s.present && s.view != View.MEETING) raiseSuspicion(s, tick);
        }
        walking = false;
        from = to;
        if (away) return;
        if (target == null && !seats.isEmpty()) {
            var summoned = summoned();
            if (summoned == null && callable(loudCall)) summoned = loudCall;
            loudCall = null;
            if (summoned != null || (!returning && tick >= nextWalk)) {
                target = summoned != null ? summoned : weightedPick();
                destination = seats.get(target).stop;
                returning = false;
                if (atDesk) {
                    // 자리에서 일어나 통로로 나온다.
                    atDesk = false;
                    step(DESK, AISLE.get(DESK_ENTRY));
                    waypoint = DESK_ENTRY;
                    chooseDirection();
                    return;
                }
                chooseDirection();
                // Starting at the same stop means walking one full lap before inspecting.
            }
        } else if (target != null && waypoint == destination) {
            inspect(tick);
            return;
        }
        if (target != null) {
            move();
        } else if (returning) {
            if (waypoint == DESK_ENTRY) {
                step(AISLE.get(DESK_ENTRY), DESK);
                returning = false;
                atDesk = true;
                nextWalk = tick + gapBeats();
            } else {
                move();
            }
        }
    }

    private void chooseDirection() {
        direction = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        if (Math.floorMod(waypoint + direction, AISLE.size()) == destination) direction = -direction;
    }

    /** 통로를 한 칸 걷는다. 자리로 돌아갈 때는 가까운 쪽으로 돈다. */
    private void move() {
        if (returning) {
            int forward = Math.floorMod(DESK_ENTRY - waypoint, AISLE.size());
            direction = forward <= AISLE.size() / 2 ? 1 : -1;
        }
        int next = Math.floorMod(waypoint + direction, AISLE.size());
        step(AISLE.get(waypoint), AISLE.get(next));
        waypoint = next;
    }

    private void step(Point start, Point end) {
        from = start;
        to = end;
        walking = true;
    }

    /** 순찰 사이 쉬는 박자. 기분이 나쁠수록 짧고(자주 돈다), 좋을수록 길다. */
    private int gapBeats() {
        int base = ThreadLocalRandom.current().nextInt(PATROL_GAP_MIN_BEATS, PATROL_GAP_MAX_BEATS);
        return Math.max(3, (int) Math.round(base * mood.gapFactor));
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

    /**
     * 마이크 소리 크기를 듣는다. 값이 바뀔 때와 매초(같은 크기가 이어질 때) 부른다.
     * @param level 0~100 @param nowMillis 지금 시각(ms)
     */
    void hearMic(UUID player, int level, long nowMillis) {
        var seat = seats.get(player);
        if (seat == null) return;
        if (level >= LOUD_LEVEL) {
            seat.quietSinceMillis = -1;
            if (seat.loudSinceMillis < 0) seat.loudSinceMillis = nowMillis;
            if (nowMillis - seat.loudSinceMillis >= LOUD_MILLIS) {
                // 한 번 들으면 다시 1초를 잰다(계속 떠들면 다음 순찰에도 또 온다).
                seat.loudSinceMillis = -1;
                if (!away && callable(player) && !player.equals(target) && loudCall == null) loudCall = player;
            }
        } else if (seat.loudSinceMillis >= 0) {
            if (seat.quietSinceMillis < 0) seat.quietSinceMillis = nowMillis;
            else if (nowMillis - seat.quietSinceMillis > LOUD_GAP_MILLIS) {
                seat.loudSinceMillis = -1;
                seat.quietSinceMillis = -1;
            }
        }
    }

    /** 부장님이 불려 갈 수 있는 사람(연결돼 있고 훈계 중이 아님). */
    private boolean callable(UUID player) {
        var seat = player == null ? null : seats.get(player);
        return seat != null && seat.present && seat.view != View.MEETING;
    }

    /** 지금 가고 있는 사람. 없으면 null. */
    UUID target() {
        return target;
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

    /**
     * 화면을 본다. 주식·메신저 창이면 훈계, 업무를 오래 놓았으면(기분이 나쁠수록 기준이 낮다) 업무 방치 훈계.
     * 기분이 아주 좋으면 걸려도 한 사람당 한 번 봐주고, 아주 나쁘면 잘못이 없어도 데려갈 때가 있다.
     */
    private void inspect(long tick) {
        Seat seat = seats.get(target);
        if (seat != null && seat.view.slacking()) {
            if (mood.forgivesOnce && forgiven.add(target)) {
                justForgiven.add(target);
            } else {
                meeting(seat, tick, MARKET_MEETING_BEATS, seat.view == View.MARKET ? "market" : "messenger");
                justCalled.add(target);
            }
        } else if (seat != null && seat.present && seat.view == View.WORK
                && seat.suspicion >= mood.idleThreshold) {
            meeting(seat, tick, IDLE_MEETING_BEATS, "idle");
            justCalled.add(target);
        } else if (seat != null && seat.present && seat.view == View.WORK
                && ThreadLocalRandom.current().nextDouble() < mood.innocentCallChance) {
            meeting(seat, tick, IDLE_MEETING_BEATS, "mood");
            justCalled.add(target);
        }
        target = null;
        returning = true;   // 다음 박자부터 자리로 걸어 돌아간다.
    }

    private static void meeting(Seat seat, long tick, int beats, String reason) {
        seat.view = View.MEETING;
        seat.returnTick = tick + beats;
        seat.meetingReason = reason;
        seat.meetings++;
        seat.suspicion = SUSPICION_AFTER_MEETING;
        seat.idleSeconds = 0;
    }

    Step step(Seat seat) {
        return new Step(from, to, seat.listener);
    }
}
