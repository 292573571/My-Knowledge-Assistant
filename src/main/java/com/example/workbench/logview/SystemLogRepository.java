package com.example.workbench.logview;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SystemLogRepository extends JpaRepository<SystemLog, Long> {

    Page<SystemLog> findByLevelInAndMessageContainingIgnoreCaseAndTimestampAfterOrderByTimestampDesc(
            List<String> levels, String keyword, Instant since, Pageable pageable);

    Page<SystemLog> findByLevelInAndTimestampAfterOrderByTimestampDesc(
            List<String> levels, Instant since, Pageable pageable);

    Page<SystemLog> findByMessageContainingIgnoreCaseAndTimestampAfterOrderByTimestampDesc(
            String keyword, Instant since, Pageable pageable);

    Page<SystemLog> findByTimestampAfterOrderByTimestampDesc(
            Instant since, Pageable pageable);

    Page<SystemLog> findByLevelInAndMessageContainingIgnoreCaseOrderByTimestampDesc(
            List<String> levels, String keyword, Pageable pageable);

    Page<SystemLog> findByLevelInOrderByTimestampDesc(List<String> levels, Pageable pageable);

    Page<SystemLog> findByMessageContainingIgnoreCaseOrderByTimestampDesc(String keyword, Pageable pageable);

    Page<SystemLog> findAllByOrderByTimestampDesc(Pageable pageable);

    @Modifying
    @Query("DELETE FROM SystemLog l WHERE l.timestamp < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
