package game.officestock.room;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Claude 답 검사: 없는 종목·이상한 행동은 버리고, 비율은 자르고, 대사는 짧게. */
class BossBrainTest {
    @Test
    void 이상한_주문은_버리고_비율은_자른다() {
        var reply = new BossBrain.Reply(List.of(
                new BossBrain.Order("MINT", "buy", 0.9),       // 사는 비율은 0.5까지
                new BossBrain.Order("FAKE", "buy", 0.2),       // 상장 안 된 종목
                new BossBrain.Order("MOON", "short", 0.3),     // 공매도 없음
                new BossBrain.Order("MOON", "sell", 2.0),      // 파는 비율은 1까지
                new BossBrain.Order("MOON", "buy", Double.NaN)),
                "오늘 감이 좋아~");
        var decisions = BossBrain.validate(reply, Set.of("MINT", "MOON"));
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(0).symbol()).isEqualTo("MINT");
        assertThat(decisions.get(0).fraction()).isEqualTo(0.5);
        assertThat(decisions.get(0).line()).isEqualTo("오늘 감이 좋아~");
        assertThat(decisions.get(1).buy()).isFalse();
        assertThat(decisions.get(1).fraction()).isEqualTo(1.0);
    }

    @Test
    void 주문이_없어도_혼잣말은_남고_긴_말은_자른다() {
        var decisions = BossBrain.validate(new BossBrain.Reply(List.of(), "가".repeat(100)), Set.of("MINT"));
        assertThat(decisions).hasSize(1);
        assertThat(decisions.getFirst().symbol()).isNull();
        assertThat(decisions.getFirst().line()).hasSize(60);
    }

    @Test
    void 키가_없으면_규칙_판단을_그대로_쓴다() throws Exception {
        var brain = new BossBrain("claude-haiku-5-5", 40);
        if (brain.enabled()) return;   // 키가 있는 환경에서는 건너뛴다(실제 호출 비용).
        var rule = List.of(new Boss.Decision("MINT", true, 0.2, null));
        assertThat(brain.decide(null, Set.of("MINT"), rule).get()).isSameAs(rule);
    }
}
