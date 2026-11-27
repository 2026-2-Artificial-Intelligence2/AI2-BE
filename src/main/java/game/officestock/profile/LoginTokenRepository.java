package game.officestock.profile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

interface LoginTokenRepository extends JpaRepository<LoginToken, String> {
    /** 이 회원의 모든 기기 로그인을 푼다(비밀번호를 복구했을 때). */
    @Modifying
    @Query("DELETE FROM LoginToken t WHERE t.profileId = :profileId")
    void deleteAllOfProfile(@Param("profileId") UUID profileId);
}
