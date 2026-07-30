package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdColumn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** 컬럼은 room_id 를 갖지 않고 table_id 로 방에 소속된다 — 조회/삭제는 방의 테이블 id 목록으로 한다. */
public interface ErdColumnRepository extends JpaRepository<ErdColumn, Long> {

    @Query("select c from ErdColumn c where c.tableId in :tableIds order by c.tableId asc, c.sortOrder asc")
    List<ErdColumn> findByTableIds(@Param("tableIds") Collection<Long> tableIds);

    @Modifying
    @Query("delete from ErdColumn c where c.tableId in :tableIds")
    void deleteByTableIds(@Param("tableIds") Collection<Long> tableIds);

    @Modifying
    @Query("delete from ErdColumn c where c.tableId = :tableId")
    void deleteByTableId(@Param("tableId") Long tableId);
}
