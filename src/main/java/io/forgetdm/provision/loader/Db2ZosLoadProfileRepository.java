package io.forgetdm.provision.loader;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface Db2ZosLoadProfileRepository extends JpaRepository<Db2ZosLoadProfileEntity, Long> {
    Optional<Db2ZosLoadProfileEntity> findByDataSourceId(Long dataSourceId);
    long countByMainframeConnectionId(Long mainframeConnectionId);
}
