package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ErdRelationRepository extends JpaRepository<ErdRelation, Long> {

    List<ErdRelation> findAllByOrderBySortOrderAsc();

    void deleteByChildTableId(Long childTableId);

    void deleteByChildTableIdOrParentTableId(Long childTableId, Long parentTableId);
}
