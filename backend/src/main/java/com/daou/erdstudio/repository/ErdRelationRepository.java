package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ErdRelationRepository extends JpaRepository<ErdRelation, Long> {

    List<ErdRelation> findByRoomIdOrderBySortOrderAsc(Long roomId);

    @Modifying
    @Query("delete from ErdRelation r where r.roomId = :roomId")
    void deleteByRoomId(@Param("roomId") Long roomId);

    @Modifying
    @Query("delete from ErdRelation r where r.childTableId = :tableId")
    void deleteByChildTableId(@Param("tableId") Long tableId);

    @Modifying
    @Query("delete from ErdRelation r where r.childTableId = :tableId or r.parentTableId = :tableId")
    void deleteAllInvolving(@Param("tableId") Long tableId);
}
