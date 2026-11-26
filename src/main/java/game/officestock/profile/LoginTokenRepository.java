package game.officestock.profile;

import org.springframework.data.jpa.repository.JpaRepository;

interface LoginTokenRepository extends JpaRepository<LoginToken, String> {}
