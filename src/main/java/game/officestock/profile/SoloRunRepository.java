package game.officestock.profile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

interface SoloRunRepository extends JpaRepository<SoloRun, Long> {
    /** 이 근무 시간, 이 기간의 판 수(올렸든 안 올렸든). */
    long countBySessionSecondsAndFinishedAtGreaterThanEqual(int sessionSeconds, Instant from);

    /** 그중 수익률이 더 높은 판 수. */
    long countBySessionSecondsAndFinishedAtGreaterThanEqualAndReturnPercentGreaterThan(int sessionSeconds, Instant from,
                                                                                    double returnPercent);

    /** 명예의 전당 한 줄: 이달 올린 판에서 기기·이름별 최고·평균 수익률과 판 수. */
    interface BoardRow {
        String getName();
        String getTag();
        double getBest();
        long getGames();
        double getAverage();
    }

    @Query(value = """
            SELECT name, tag, MAX(return_percent) AS best, COUNT(*) AS games, AVG(return_percent) AS average
            FROM solo_run
            WHERE shared_at IS NOT NULL AND session_seconds = :seconds AND finished_at >= :from
            GROUP BY device_hash, name, tag
            ORDER BY best DESC, games ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<BoardRow> board(@Param("seconds") int seconds, @Param("from") Instant from, @Param("limit") int limit);
}
