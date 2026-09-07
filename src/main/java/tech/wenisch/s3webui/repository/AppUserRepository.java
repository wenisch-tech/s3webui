package tech.wenisch.s3webui.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.s3webui.entity.AppUser;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<AppUser> findAllByOrderByEmailAsc();
}
