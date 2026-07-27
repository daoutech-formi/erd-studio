package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ErdDomainRepository extends JpaRepository<ErdDomain, String> {

    List<ErdDomain> findAllByOrderBySortOrderAsc();
}
