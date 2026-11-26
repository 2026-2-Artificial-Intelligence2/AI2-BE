package game.officestock.room;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameRoomTest {
    @Test
    void 멀티는_두_명부터_네_명까지_방장만_시작한다() {
        var host = UUID.randomUUID();
        var room = new GameRoom(host);
        assertThatThrownBy(() -> room.start(host, Instant.now())).hasMessageContaining("두 명");
        var guest = UUID.randomUUID();
        room.join(guest);
        assertThatThrownBy(() -> room.start(guest, Instant.now())).hasMessageContaining("방장");
        room.join(UUID.randomUUID());
        room.join(UUID.randomUUID());
        assertThatThrownBy(() -> room.join(UUID.randomUUID())).hasMessageContaining("가득");
        room.start(host, Instant.now());
        assertThat(room.snapshot().startedAt()).isNotNull();
        assertThatThrownBy(() -> room.join(UUID.randomUUID())).hasMessageContaining("시작한");
    }

    @Test
    void 솔로는_혼자_시작하고_아무도_못_들어온다() {
        var host = UUID.randomUUID();
        var room = new GameRoom(host, true);
        assertThatThrownBy(() -> room.join(UUID.randomUUID())).hasMessageContaining("혼자");
        room.start(host, Instant.now());
        assertThat(room.snapshot().players()).containsExactly(host);
    }
}
