package game.officestock.player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class WorkDesk {
    private final PlayerWallet wallet;
    private final long rewardWon;
    private Task current;

    // 플레이테스트 기록: 업무 1건에 실제로 얼마나 걸리는지 잰다.
    private long taskStartedNanos;
    private long workingNanos;
    private long lastObservedNanos;
    private boolean lastWorking = true;
    private int wrongAnswers;
    private final List<TaskTiming> timings = new ArrayList<>();

    public WorkDesk(long startingCashWon, long rewardWon, long nowNanos) {
        if (rewardWon <= 0) {
            throw new IllegalArgumentException("업무 수당은 양수여야 합니다.");
        }
        this.wallet = new PlayerWallet(startingCashWon);
        this.rewardWon = rewardWon;
        this.current = nextTask(1);
        this.taskStartedNanos = nowNanos;
        this.lastObservedNanos = nowNanos;
    }

    public record Task(long number, int a1, int a2) {}
    public record State(Task task, PlayerWallet.Balance balance, long rewardWon) {}

    /**
     * 업무 1건의 기록. elapsed는 배정부터 완료까지 전체 시간, working은 그중 업무 창을 보고 있던 시간이다.
     * 수당은 working 기준으로 정한다(주식 창·훈계 시간은 빠진다).
     */
    public record TaskTiming(long number, long elapsedMillis, long workingMillis, int wrongAnswers) {}

    public State state() {
        return new State(current, wallet.balance(), rewardWon);
    }

    public PlayerWallet wallet() {
        return wallet;
    }

    public List<TaskTiming> timings() {
        return List.copyOf(timings);
    }

    /** 화면 상태를 알린다. 업무 창을 보고 있던 구간만 working 시간에 더한다. 화면이 바뀔 때와 매 틱 부른다. */
    public void observeView(boolean working, long nowNanos) {
        if (lastWorking) workingNanos += nowNanos - lastObservedNanos;
        lastObservedNanos = nowNanos;
        lastWorking = working;
    }

    /** @return 새로 완료한 업무의 기록, 이미 처리한 업무 번호면 null */
    public TaskTiming submit(long number, int answer, long nowNanos) {
        if (number < current.number() && number > 0) return null;
        if (number != current.number()) {
            throw new IllegalArgumentException("현재 업무 번호를 확인하세요.");
        }
        if (answer != current.a1() + current.a2()) {
            wrongAnswers++;
            throw new IllegalArgumentException("합계가 맞지 않습니다. 다시 계산해 주세요.");
        }
        wallet.rewardCompletedTask(number, rewardWon);
        observeView(lastWorking, nowNanos);
        var timing = new TaskTiming(number, (nowNanos - taskStartedNanos) / 1_000_000,
                workingNanos / 1_000_000, wrongAnswers);
        timings.add(timing);
        current = nextTask(number + 1);
        taskStartedNanos = nowNanos;
        workingNanos = 0;
        wrongAnswers = 0;
        return timing;
    }

    private static Task nextTask(long number) {
        var random = ThreadLocalRandom.current();
        return new Task(number, random.nextInt(10, 100), random.nextInt(10, 100));
    }
}
