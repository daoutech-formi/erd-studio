package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdColumn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ErdColumnRepository extends JpaRepository<ErdColumn, Long> {

    List<ErdColumn> findAllByOrderByTableIdAscSortOrderAsc();

    void deleteByTableId(Long tableId);
}
