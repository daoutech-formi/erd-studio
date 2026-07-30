package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ErdTableRepository extends JpaRepository<ErdTable, Long> {

    List<ErdTable> findByRoomIdOrderBySortOrderAsc(Long roomId);

    Optional<ErdTable> findByRoomIdAndName(Long roomId, String name);

    boolean existsByRoomIdAndName(Long roomId, String name);

    long countByRoomId(Long roomId);

    @Modifying
    @Query("delete from ErdTable t where t.roomId = :roomId")
    void deleteByRoomId(@Param("roomId") Long roomId);
}
