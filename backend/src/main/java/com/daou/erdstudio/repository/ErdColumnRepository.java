package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdColumn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ErdColumnRepository extends JpaRepository<ErdColumn, Long> {

    List<ErdColumn> findAllByOrderByTableIdAscSortOrderAsc();

    @Modifying
    @Query("delete from ErdColumn c where c.tableId = :tableId")
    void deleteByTableId(@Param("tableId") Long tableId);
}
