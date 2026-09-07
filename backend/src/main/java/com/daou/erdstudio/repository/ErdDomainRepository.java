package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ErdDomainRepository extends JpaRepository<ErdDomain, Long> {

    List<ErdDomain> findByRoomIdOrderBySortOrderAsc(Long roomId);

    boolean existsByRoomIdAndKey(Long roomId, String key);

    /** 키가 어느 방에든 존재하는지 — 오타(거부)와 다른 방의 낡은 키(보정)를 구분할 때 쓴다. */
    boolean existsByKey(String key);

    @Modifying
    @Query("delete from ErdDomain d where d.roomId = :roomId")
    void deleteByRoomId(@Param("roomId") Long roomId);
}
