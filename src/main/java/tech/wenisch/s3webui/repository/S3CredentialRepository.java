package tech.wenisch.s3webui.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.s3webui.entity.S3Credential;

import java.util.List;
import java.util.Optional;

public interface S3CredentialRepository extends JpaRepository<S3Credential, Long> {

    @EntityGraph(attributePaths = "grants")
    List<S3Credential> findAllByOrderByNameAsc();

    @EntityGraph(attributePaths = "grants")
    List<S3Credential> findByEnabledTrueOrderByNameAsc();

    Optional<S3Credential> findByNameIgnoreCase(String name);
}
