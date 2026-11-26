package game.officestock.player;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class TaskFactoryTest {
    @Test
    @SuppressWarnings("unchecked")
    void 엑셀_합계는_100_단위_두세_개이고_정답은_그_합이다() {
        var random = new SplittableRandom(1);
        int sheets = 0;
        for (int i = 0; i < 500; i++) {
            var task = TaskFactory.make(random);
            if (!task.kind().equals("sheet")) continue;
            sheets++;
            var rows = (List<Map<String, Object>>) task.data().get("rows");
            assertThat(rows).hasSizeBetween(2, 3);
            long sum = 0;
            for (var row : rows) {
                long amount = (Long) row.get("amount");
                assertThat(amount % 100).isZero();
                assertThat(amount).isBetween(100L, 900L);
                sum += amount;
            }
            assertThat(task.answer()).isEqualTo(Long.toString(sum));
        }
        assertThat(sheets).isGreaterThan(100);
    }

    @Test
    void 결재_정답은_규정의_한도와_맞다() {
        var random = new SplittableRandom(2);
        int approve = 0, total = 0;
        for (int i = 0; i < 1_000; i++) {
            var task = TaskFactory.make(random);
            if (!task.kind().equals("approval")) continue;
            total++;
            long amount = ((Number) task.data().get("amountWon")).longValue();
            var people = (Integer) task.data().get("people");
            String rule = (String) task.data().get("rule");
            long limit = limitOf(rule);
            boolean ok = people != null ? amount / (double) people <= limit : amount <= limit;
            assertThat(task.answer()).as(rule + " / " + amount).isEqualTo(ok ? "APPROVE" : "REJECT");
            if (ok) approve++;
        }
        assertThat((double) approve / total).isBetween(0.3, 0.7);
    }

    /** 규정 문장의 한도(원). "50만 원", "15,000원" 형태. */
    private static long limitOf(String rule) {
        var man = java.util.regex.Pattern.compile("(\\d+)만 원").matcher(rule);
        if (man.find()) return Long.parseLong(man.group(1)) * 10_000;
        var won = java.util.regex.Pattern.compile("([\\d,]+)원").matcher(rule);
        if (won.find()) return Long.parseLong(won.group(1).replace(",", ""));
        throw new AssertionError("한도를 찾을 수 없음: " + rule);
    }

    @Test
    void 한_사람에게_메일은_48통을_다_보기_전에는_반복되지_않는다() {
        var random = new SplittableRandom(3);
        var deck = new ArrayDeque<Integer>();
        var seen = new HashSet<String>();
        int mails = 0;
        while (mails < TaskFactory.FOLDERS.size() * 12) {
            var task = TaskFactory.make(random, deck);
            if (!task.kind().equals("mail")) continue;
            mails++;
            assertThat(seen.add((String) task.data().get("subject"))).isTrue();
            assertThat(TaskFactory.FOLDERS).contains(task.answer());
        }
    }
}
