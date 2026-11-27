package game.officestock.profile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface GameResultRepository extends JpaRepository<GameResult, Long> {
    /** 명예의 전당 한 줄: 이 근무 시간 솔로 판에서 사람별 최고·평균 수익률과 판 수. */
    interface BoardRow {
        String getName();
        double getBest();
        long getGames();
        double getAverage();
    }

    @Query(value = """
            SELECT p.name AS name, MAX(r.return_percent) AS best, COUNT(*) AS games, AVG(r.return_percent) AS average
            FROM game_result r JOIN profile p ON p.id = r.profile_id
            WHERE r.mode = 'solo' AND r.session_seconds = :seconds
            GROUP BY p.id, p.name
            ORDER BY best DESC, games ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<BoardRow> board(@Param("seconds") int seconds, @Param("limit") int limit);

    /** 내 전적의 최근 판들(최근 것부터). */
    List<GameResult> findTop20ByProfileIdOrderByFinishedAtDesc(UUID profileId);

    /** 내 전적 요약: 모드별 판 수, 1위 횟수, 최고·평균 수익률, 손익 합계. */
    interface Summary {
        long getGames();
        long getSoloGames();
        long getMultiGames();
        long getMultiWins();
        Double getBest();
        Double getAverage();
        Long getTotalProfit();
        long getBankrupt();
    }

    @Query(value = """
            SELECT COUNT(*) AS games,
                   COUNT(*) FILTER (WHERE mode = 'solo') AS "soloGames",
                   COUNT(*) FILTER (WHERE mode = 'multi') AS "multiGames",
                   COUNT(*) FILTER (WHERE mode = 'multi' AND rank = 1) AS "multiWins",
                   MAX(return_percent) AS best,
                   AVG(return_percent) AS average,
                   SUM(net_worth_won - starting_cash_won) AS "totalProfit",
                   COUNT(*) FILTER (WHERE bankrupt) AS bankrupt
            FROM game_result WHERE profile_id = :profileId
            """, nativeQuery = true)
    Summary summary(@Param("profileId") UUID profileId);
}
