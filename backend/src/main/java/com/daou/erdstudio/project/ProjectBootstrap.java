package com.daou.erdstudio.project;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 기동 시 프로젝트 기반을 보장한다(멱등).
 * ① legacy 프로젝트 생성 ② project_id 가 비어 있는 기존 방을 legacy 로 백필
 * ③ 구버전의 erd_room(name) 전역 유니크 제약 드랍(이름 유니크는 프로젝트 내로 좁힘).
 * SeedLoader(기본 방 생성)보다 먼저 실행되어야 하므로 @Order 로 순서를 고정한다.
 */
@Component
@Order(ProjectBootstrap.ORDER)
public class ProjectBootstrap implements ApplicationRunner {

    public static final int ORDER = 0;

    private static final Logger log = LoggerFactory.getLogger(ProjectBootstrap.class);

    private final ProjectService projectService;
    private final EntityManager entityManager;
    private final TransactionTemplate tx;

    public ProjectBootstrap(ProjectService projectService, EntityManager entityManager,
                            PlatformTransactionManager transactionManager) {
        this.projectService = projectService;
        this.entityManager = entityManager;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        // 백필과 제약 드랍은 트랜잭션을 분리한다 — 드랍 쿼리는 H2(테스트)에서 실패해도
        // 백필 커밋에 영향을 주면 안 된다(같은 트랜잭션이면 rollback-only 로 오염된다).
        tx.executeWithoutResult(status -> {
            Long legacyId = projectService.ensureLegacy().getId();
            int backfilled = entityManager
                    .createNativeQuery("UPDATE erd_room SET project_id = ?1 WHERE project_id IS NULL")
                    .setParameter(1, legacyId)
                    .executeUpdate();
            if (backfilled > 0) {
                log.info("기존 방 {}개를 legacy 프로젝트로 백필했습니다.", backfilled);
            }
        });
        dropGlobalNameUnique();
    }

    /**
     * Hibernate 가 과거에 만든 erd_room(name) 단일 컬럼 유니크 제약을 찾아 지운다.
     * PostgreSQL 전용 카탈로그 조회라 H2 테스트에서는 실패를 무시한다. 재실행은 결과가 비어 무해하다.
     */
    private void dropGlobalNameUnique() {
        try {
            List<?> names = tx.execute(status -> entityManager.createNativeQuery("""
                    SELECT c.conname FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                    WHERE c.conrelid = 'erd_room'::regclass AND c.contype = 'u'
                      AND array_length(c.conkey, 1) = 1 AND a.attname = 'name'
                    """).getResultList());
            if (names == null) {
                return;
            }
            for (Object name : names) {
                tx.executeWithoutResult(status -> entityManager
                        .createNativeQuery("ALTER TABLE erd_room DROP CONSTRAINT \"" + name + "\"")
                        .executeUpdate());
                log.info("erd_room(name) 전역 유니크 제약을 드랍했습니다: {}", name);
            }
        } catch (RuntimeException e) {
            log.warn("erd_room(name) 전역 유니크 제약 정리를 건너뜁니다: {}", e.getMessage());
        }
    }
}
