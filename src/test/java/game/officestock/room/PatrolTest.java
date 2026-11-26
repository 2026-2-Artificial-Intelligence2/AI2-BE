package game.officestock.room;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 의심 게이지: 마지막 업무 뒤 20초 유예, 40초까지 초당 +1, 그 뒤 +2, 업무 한 건에 −20. */
class PatrolTest {
    @Test
    void 이십초까지는_오르지_않고_사십초에_20이_된다() {
        var player = UUID.randomUUID();
        var patrol = new Patrol();
        patrol.start(List.of(player));
        for (int i = 1; i <= 10; i++) patrol.advance(i);   // 한 박자 = 2초
        assertThat(patrol.seats.get(player).suspicion).isZero();
        for (int i = 11; i <= 20; i++) patrol.advance(i);
        assertThat(patrol.seats.get(player).suspicion).isEqualTo(20);
    }

    @Test
    void 업무를_안_하면_의심이_오르고_업무를_하면_20_내려간다() {
        var player = UUID.randomUUID();
        var patrol = new Patrol();
        patrol.start(List.of(player));
        for (int i = 1; i <= 20; i++) patrol.advance(i);   // 40초
        var seat = patrol.seats.get(player);
        assertThat(seat.suspicion).isEqualTo(20);
        patrol.advance(21);                               // 42초: 40초 넘으면 초당 +2
        assertThat(seat.suspicion).isEqualTo(24);
        patrol.onWorkDone(player);
        assertThat(seat.suspicion).isEqualTo(4);
        assertThat(seat.idleSeconds).isZero();
    }

    @Test
    void 부장님이_자리를_비운_동안과_연결이_끊긴_동안은_오르지_않는다() {
        var here = UUID.randomUUID();
        var gone = UUID.randomUUID();
        var patrol = new Patrol();
        patrol.start(List.of(here, gone));
        patrol.seats.get(gone).present = false;
        patrol.setAway(true, 0);
        for (int i = 1; i <= 30; i++) patrol.advance(i);
        assertThat(patrol.seats.get(here).suspicion).isZero();
        patrol.setAway(false, 30);
        for (int i = 31; i <= 60; i++) patrol.advance(i);
        assertThat(patrol.seats.get(here).suspicion).isPositive();
        assertThat(patrol.seats.get(gone).suspicion).isZero();
    }
}
