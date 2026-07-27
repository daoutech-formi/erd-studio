package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdTable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ErdTableRepository extends JpaRepository<ErdTable, Long> {

    List<ErdTable> findAllByOrderBySortOrderAsc();

    Optional<ErdTable> findByName(String name);

    boolean existsByName(String name);
}
