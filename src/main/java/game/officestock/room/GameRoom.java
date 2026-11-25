package game.officestock.room;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class GameRoom {
    private static final int MIN_PLAYERS = 2;
    private static final int MAX_PLAYERS = 4;

    private final UUID hostId;
    private final Set<UUID> players = new LinkedHashSet<>();
    private Instant startedAt;

    public GameRoom(UUID hostId) {
        this.hostId = Objects.requireNonNull(hostId);
        players.add(hostId);
    }

    public synchronized void join(UUID playerId) {
        Objects.requireNonNull(playerId);
        if (startedAt != null) {
            throw new IllegalStateException("이미 시작한 방입니다.");
        }
        if (players.contains(playerId)) return;
        if (players.size() >= MAX_PLAYERS) {
            throw new IllegalStateException("방이 가득 찼습니다.");
        }
        players.add(playerId);
    }

    public synchronized void start(UUID requesterId, Instant serverTime) {
        if (!hostId.equals(requesterId)) {
            throw new IllegalStateException("방장만 시작할 수 있습니다.");
        }
        if (startedAt != null) return;
        if (players.size() < MIN_PLAYERS) {
            throw new IllegalStateException("두 명 이상 모이면 시작할 수 있습니다.");
        }
        startedAt = Objects.requireNonNull(serverTime);
    }

    public synchronized void leave(UUID playerId) {
        players.remove(playerId);
    }


    public synchronized Snapshot snapshot() {
        return new Snapshot(hostId, List.copyOf(players), startedAt);
    }

    public record Snapshot(UUID hostId, List<UUID> players, Instant startedAt) {}
}
