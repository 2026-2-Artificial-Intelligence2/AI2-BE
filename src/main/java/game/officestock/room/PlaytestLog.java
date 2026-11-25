package game.officestock.room;

import game.officestock.player.WorkDesk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 플레이테스트용 업무 시간 기록. 업무 1건마다 CSV에 한 줄을 붙이고, 퇴장할 때 요약을 로그에 남긴다. */
@Component
public class PlaytestLog {
    private static final Logger log = LoggerFactory.getLogger(PlaytestLog.class);
    private static final String HEADER =
            "recorded_at,room,player,task,elapsed_ms,working_ms,wrong_answers\n";
    private final Path file;

    public PlaytestLog(@Value("${game.playtest-log}") String file) {
        this.file = Path.of(file).toAbsolutePath();
        log.info("업무 시간 기록 파일: {}", this.file);
    }

    public synchronized void task(String room, String player, WorkDesk.TaskTiming timing) {
        var line = String.join(",", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                room, player, Long.toString(timing.number()), Long.toString(timing.elapsedMillis()),
                Long.toString(timing.workingMillis()), Integer.toString(timing.wrongAnswers())) + "\n";
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            if (Files.notExists(file)) Files.writeString(file, HEADER, StandardCharsets.UTF_8);
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("업무 시간 기록 실패", e);
        }
    }

    /** 첫 업무는 게임 시작 직후라 화면을 익히는 시간이 섞이므로 요약에서 뺀다. */
    public void summary(String room, String player, List<WorkDesk.TaskTiming> timings) {
        var working = timings.stream().filter(t -> t.number() > 1)
                .mapToLong(WorkDesk.TaskTiming::workingMillis).sorted().toArray();
        if (working.length == 0) return;
        long median = working[working.length / 2];
        long wrong = timings.stream().mapToLong(WorkDesk.TaskTiming::wrongAnswers).sum();
        log.info("[업무 시간] 방 {} {}: {}건 완료, 업무 창 기준 중앙값 {}초, 오답 {}회",
                room, player, timings.size(), String.format("%.1f", median / 1000.0), wrong);
    }
}
