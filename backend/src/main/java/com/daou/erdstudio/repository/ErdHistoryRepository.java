package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ErdHistoryRepository extends JpaRepository<ErdHistory, Long> {

    List<ErdHistory> findByRoomIdOrderByIdDesc(Long roomId, Pageable pageable);

    Optional<ErdHistory> findByIdAndRoomId(Long id, Long roomId);

    @Query("select h.id from ErdHistory h where h.roomId = :roomId order by h.id desc")
    List<Long> findIdsByRoomIdOrderByIdDesc(@Param("roomId") Long roomId);

    @Modifying
    @Query("delete from ErdHistory h where h.roomId = :roomId")
    void deleteByRoomId(@Param("roomId") Long roomId);
}
