package com.daou.erdstudio.repository;

import com.daou.erdstudio.domain.ErdRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ErdRoomRepository extends JpaRepository<ErdRoom, Long> {

    List<ErdRoom> findAllByOrderByIdAsc();

    List<ErdRoom> findByCreatorClientKey(String creatorClientKey);

    boolean existsByName(String name);
}
