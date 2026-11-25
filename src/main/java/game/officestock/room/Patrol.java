package game.officestock.room;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class Patrol {
    enum View { WORK, MARKET, MEETING }

    record Point(double x, double z) {}
    record Listener(Point position, int forwardZ) {}
    record Step(Point from, Point to, Listener listener) {}

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

    void advance(long tick) {
        beat = tick;
        for (Seat s : seats.values()) {
            if (s.view == View.MEETING && tick >= s.returnTick) s.view = View.WORK;
        }
        walking = false;
        if (target == null && tick >= nextWalk && !seats.isEmpty()) {
            var candidates = List.copyOf(seats.keySet());
            target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            destination = seats.get(target).stop;
            direction = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
            if (Math.floorMod(waypoint + direction, AISLE.size()) == destination) {
                direction = -direction;
            }
            // Starting at the same stop means walking one full lap before inspecting.
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

    private void inspect(long tick) {
        Seat seat = seats.get(target);
        if (seat != null && seat.view == View.MARKET) {
            seat.view = View.MEETING;
            seat.returnTick = tick + 5;
        }
        target = null;
        nextWalk = tick + ThreadLocalRandom.current().nextInt(6, 12);
    }

    Step step(Seat seat) {
        return new Step(AISLE.get(previous), AISLE.get(waypoint), seat.listener);
    }
}
