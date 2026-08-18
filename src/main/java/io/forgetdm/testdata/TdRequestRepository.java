package io.forgetdm.testdata;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TdRequestRepository extends JpaRepository<TdRequestEntity, Long> {
    List<TdRequestEntity> findByOwnerUsernameOrderByCreatedAtDesc(String ownerUsername, Pageable page);
    List<TdRequestEntity> findAllByOrderByCreatedAtDesc(Pageable page);
}
