package tech.wenisch.s3webui.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import tech.wenisch.s3webui.entity.AuditLogEntry;

import java.util.List;

public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntry, Long> {

    List<AuditLogEntry> findAllByOrderByIdDesc(Pageable pageable);

    List<AuditLogEntry> findByUserEmailIgnoreCaseOrderByIdDesc(String userEmail, Pageable pageable);

    /** Ids newest first; used to find the retention cut-off. */
    @Query("select e.id from AuditLogEntry e order by e.id desc")
    List<Long> findIdsNewestFirst(Pageable pageable);

    @Modifying
    void deleteByIdLessThan(Long id);
}
