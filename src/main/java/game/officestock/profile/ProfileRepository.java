package game.officestock.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ProfileRepository extends JpaRepository<Profile, UUID> {
    Optional<Profile> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
