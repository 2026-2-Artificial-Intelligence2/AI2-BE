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

    @Test
    void 크게_2초_떠들면_부장님이_그_사람에게_간다() {
        var quiet = UUID.randomUUID();
        var loud = UUID.randomUUID();
        var patrol = new Patrol();
        patrol.start(List.of(quiet, loud));
        patrol.hearMic(loud, 85, 0);
        patrol.hearMic(loud, 60, 1_000);      // 말 사이 0.4초 조용함은 이어진 것으로 본다
        patrol.hearMic(loud, 90, 1_400);
        patrol.hearMic(loud, 90, 1_999);
        patrol.advance(1);
        assertThat(patrol.target()).isNull();  // 아직 2초가 안 됐고 순찰 간격 전이다
        patrol.hearMic(loud, 90, 2_000);
        patrol.advance(2);
        assertThat(patrol.target()).isEqualTo(loud);
        assertThat(patrol.seats.get(loud).view).isEqualTo(Patrol.View.WORK);   // 떠든 것만으로 훈계하지 않는다
    }

    @Test
    void 조용한_틈이_길거나_80보다_작으면_부르지_않는다() {
        var player = UUID.randomUUID();
        var patrol = new Patrol();
        patrol.start(List.of(player));
        patrol.hearMic(player, 79, 0);
        patrol.hearMic(player, 79, 5_000);
        patrol.hearMic(player, 85, 6_000);
        patrol.hearMic(player, 30, 7_000);
        patrol.hearMic(player, 30, 7_600);    // 0.6초 조용함: 처음부터 다시 잰다
        patrol.hearMic(player, 85, 7_700);
        patrol.hearMic(player, 85, 9_000);
        patrol.advance(1);
        assertThat(patrol.target()).isNull();
    }

    @Test
    void 부장님이_자리를_비웠으면_소리를_듣지_못한다() {
        var player = UUID.randomUUID();
        var patrol = new Patrol();
        patrol.start(List.of(player));
        patrol.setAway(true, 0);
        patrol.hearMic(player, 95, 0);
        patrol.hearMic(player, 95, 3_000);
        patrol.setAway(false, 1);
        patrol.advance(1);
        assertThat(patrol.target()).isNull();
    }
}
