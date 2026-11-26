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
    /** 솔로 방은 방장 혼자 시작하고 아무도 들어올 수 없다. */
    private final boolean solo;
    private final Set<UUID> players = new LinkedHashSet<>();
    private Instant startedAt;

    public GameRoom(UUID hostId) {
        this(hostId, false);
    }

    public GameRoom(UUID hostId, boolean solo) {
        this.hostId = Objects.requireNonNull(hostId);
        this.solo = solo;
        players.add(hostId);
    }

    public synchronized void join(UUID playerId) {
        Objects.requireNonNull(playerId);
        if (startedAt != null) {
            throw new IllegalStateException("이미 시작한 방입니다.");
        }
        if (players.contains(playerId)) return;
        if (solo) throw new IllegalStateException("혼자 하는 방이라 들어갈 수 없습니다.");
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
        if (!solo && players.size() < MIN_PLAYERS) {
            throw new IllegalStateException("두 명 이상 모이면 시작할 수 있습니다.");
        }
        startedAt = Objects.requireNonNull(serverTime);
    }

    public synchronized void leave(UUID playerId) {
        players.remove(playerId);
    }

    /** 같은 방에서 다시 하기: 참가자는 그대로 두고 시작 전 대기 상태로 돌린다. 방장만 할 수 있다. */
    public synchronized void reset(UUID requesterId) {
        if (!hostId.equals(requesterId)) {
            throw new IllegalStateException("방장만 다시 시작할 수 있습니다.");
        }
        startedAt = null;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(hostId, List.copyOf(players), startedAt);
    }

    public record Snapshot(UUID hostId, List<UUID> players, Instant startedAt) {}
}
