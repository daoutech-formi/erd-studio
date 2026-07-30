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

    @Modifying
    @Query("delete from ErdDomain d where d.roomId = :roomId")
    void deleteByRoomId(@Param("roomId") Long roomId);
}
