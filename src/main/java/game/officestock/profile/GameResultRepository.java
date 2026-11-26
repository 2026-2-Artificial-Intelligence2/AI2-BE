package game.officestock.profile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

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
}
