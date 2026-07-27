package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ErdHistoryRepository extends JpaRepository<ErdHistory, Long> {

    List<ErdHistory> findAllByOrderByIdDesc(Pageable pageable);

    @Query("select h.id from ErdHistory h order by h.id desc")
    List<Long> findIdsByOrderByIdDesc();
}
