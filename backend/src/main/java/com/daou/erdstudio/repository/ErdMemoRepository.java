package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdMemo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ErdMemoRepository extends JpaRepository<ErdMemo, Long> {

    List<ErdMemo> findByRoomIdOrderBySortOrderAsc(Long roomId);

    Optional<ErdMemo> findByRoomIdAndMemoKey(Long roomId, String memoKey);

    boolean existsByRoomIdAndMemoKey(Long roomId, String memoKey);

    long countByRoomId(Long roomId);

    @Modifying
    @Query("delete from ErdMemo m where m.roomId = :roomId")
    void deleteByRoomId(@Param("roomId") Long roomId);
}
