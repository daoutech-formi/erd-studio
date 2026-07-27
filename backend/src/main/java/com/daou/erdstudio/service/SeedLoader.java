package com.daou.erdstudio.service;

import com.daou.erdstudio.web.dto.SchemaDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/** 최초 기동 시 DB가 비어 있으면 classpath의 시드 스키마를 적재한다. */
@Component
public class SeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);
    private static final String SEED_PATH = "seed/schema.json";

    private final SchemaService schemaService;
    private final ObjectMapper objectMapper;

    public SeedLoader(SchemaService schemaService, ObjectMapper objectMapper) {
        this.schemaService = schemaService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!schemaService.isEmpty()) {
            return;
        }
        ClassPathResource seed = new ClassPathResource(SEED_PATH);
        if (!seed.exists()) {
            log.info("시드 파일이 없어 빈 스키마로 시작합니다.");
            return;
        }
        try (InputStream in = seed.getInputStream()) {
            SchemaDoc doc = objectMapper.readValue(in, SchemaDoc.class);
            schemaService.replaceAll(doc);
            log.info("시드 데이터 적재 완료: 테이블 {}개, 관계 {}개", doc.tables().size(), doc.relations().size());
        }
    }
}
