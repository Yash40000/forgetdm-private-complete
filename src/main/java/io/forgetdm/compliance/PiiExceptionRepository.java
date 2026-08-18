package io.forgetdm.compliance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PiiExceptionRepository extends JpaRepository<PiiExceptionEntity, Long> {
    List<PiiExceptionEntity> findAllByOrderByCreatedAtDesc();
    List<PiiExceptionEntity> findByDataSourceIdOrderByCreatedAtDesc(Long dataSourceId);
    List<PiiExceptionEntity> findByStatus(String status);
}
