package tech.wenisch.s3webui.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.s3webui.entity.MigrationJob;
import tech.wenisch.s3webui.model.MigrationStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface MigrationJobRepository extends JpaRepository<MigrationJob, String> {

    List<MigrationJob> findByOwnerNameOrderByCreatedAtDesc(String ownerName, Pageable pageable);

    List<MigrationJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<MigrationJob> findByStatusIn(Collection<MigrationStatus> statuses);

    @Transactional
    long deleteByFinishedAtBefore(Instant cutoff);
}
