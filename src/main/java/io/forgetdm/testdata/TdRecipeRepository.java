package io.forgetdm.testdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TdRecipeRepository extends JpaRepository<TdRecipeEntity, Long> {
    List<TdRecipeEntity> findAllByOrderBySortOrderAsc();
}
