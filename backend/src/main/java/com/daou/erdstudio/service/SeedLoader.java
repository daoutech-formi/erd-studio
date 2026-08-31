package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdRoom;
import com.daou.erdstudio.project.ProjectBootstrap;
import com.daou.erdstudio.project.ProjectService;
import com.daou.erdstudio.repository.ErdRoomRepository;
import com.daou.erdstudio.web.dto.SchemaDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * 최초 기동 시 방이 하나도 없으면 기본 방을 만들고 classpath 의 시드 스키마를 적재한다.
 * 방이 이미 있으면 아무것도 하지 않는다.
 *
 * <p>기존 배포 DB 업그레이드 시 주의: erd_domain/erd_table/erd_relation/erd_history 에
 * room_id(NOT NULL) 가 추가되었으므로, ddl-auto=update 만으로는 데이터가 있는 테이블에
 * 컬럼을 추가할 수 없다. erd_* 테이블을 drop 후 재생성하거나 room_id 를 수동 백필해야 한다.
 */
@Component
@Order(ProjectBootstrap.ORDER + 1)   // legacy 프로젝트가 먼저 만들어진 뒤 실행된다.
public class SeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);
    private static final String SEED_PATH = "seed/schema.json";
    private static final String DEFAULT_ROOM_NAME = "애드콘 ERD";

    private final ErdRoomRepository roomRepository;
    private final SchemaService schemaService;
    private final ObjectMapper objectMapper;
    private final ProjectService projectService;

    public SeedLoader(ErdRoomRepository roomRepository, SchemaService schemaService,
                      ObjectMapper objectMapper, ProjectService projectService) {
        this.roomRepository = roomRepository;
        this.schemaService = schemaService;
        this.objectMapper = objectMapper;
        this.projectService = projectService;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (roomRepository.count() > 0) {
            return;
        }
        ErdRoom room = roomRepository.save(
                new ErdRoom(DEFAULT_ROOM_NAME, "system", null, projectService.ensureLegacy().getId()));
        ClassPathResource seed = new ClassPathResource(SEED_PATH);
        if (!seed.exists()) {
            log.info("시드 파일이 없어 빈 방('{}')으로 시작합니다.", DEFAULT_ROOM_NAME);
            return;
        }
        try (InputStream in = seed.getInputStream()) {
            SchemaDoc doc = objectMapper.readValue(in, SchemaDoc.class);
            schemaService.replaceAll(room.getId(), doc);
            log.info("기본 방 '{}' 시드 적재 완료: 테이블 {}개, 관계 {}개",
                    DEFAULT_ROOM_NAME, doc.tables().size(), doc.relations().size());
        }
    }
}
