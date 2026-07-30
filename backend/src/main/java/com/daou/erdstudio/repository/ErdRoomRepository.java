package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ErdRoomRepository extends JpaRepository<ErdRoom, Long> {

    List<ErdRoom> findAllByOrderByIdAsc();

    boolean existsByName(String name);
}
