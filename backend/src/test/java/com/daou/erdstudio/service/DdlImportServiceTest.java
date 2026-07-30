package com.daou.erdstudio.service;

import com.daou.erdstudio.service.DdlImportService.ImportPlan;
import com.daou.erdstudio.web.dto.SchemaDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class DdlImportServiceTest {

    @Autowired
    DdlImportService importService;
    @Autowired
    SchemaService schemaService;

    @BeforeEach
    void seed() {
        Map<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        domains.put("user", new SchemaDoc.DomainDef("회원", "#4f8cff"));
        domains.put("stat", new SchemaDoc.DomainDef("통계/기타", "#6b7488"));
        SchemaDoc doc = new SchemaDoc(
                domains,
                List.of(
                        Arrays.asList("donut_user", "user", "회원 정보", true, 100.0, 200.0),
                        Arrays.asList("legacy_table", "stat", "구 테이블", false, null, null)),
                List.of(List.of("legacy_table", "donut_user")),
                Map.of(
                        "donut_user", List.of(
                                List.of("user_no", "int", "회원번호", "PK"),
                                List.of("email", "varchar(131)", "이메일")),
                        "legacy_table", List.of(
                                List.of("id", "int", "번호", "PK"),
                                List.of("user_no", "int", "회원번호", "FK"))));
        schemaService.replaceAll(doc);
    }

    private static final String DDL = """
            CREATE TABLE `donut_user` (
              `user_no` int(11) NOT NULL COMMENT '회원번호',
              `email` varchar(131) NOT NULL COMMENT '이메일주소',
              `new_col` char(1) DEFAULT 'N' COMMENT '신규 컬럼',
              PRIMARY KEY (`user_no`)
            ) COMMENT='회원 정보 v2';

            CREATE TABLE `brand_new` (
              `brand_no` int(11) NOT NULL,
              PRIMARY KEY (`brand_no`)
            ) COMMENT='신규 테이블';
            """;

    @Test
    void merge_기존_유지_신규_추가_컬럼_갱신() {
        ImportPlan plan = importService.plan(DDL, "merge");
        assertThat(plan.summary().added()).containsExactly("brand_new");
        assertThat(plan.summary().updated()).containsExactly("donut_user");
        assertThat(plan.summary().removed()).isEmpty();
        // legacy_table 은 그대로 유지
        assertThat(plan.doc().tables()).hasSize(3);
        // donut_user 의 위치·도메인·허브 보존
        List<Object> user = plan.doc().tables().stream()
                .filter(r -> "donut_user".equals(r.get(0))).findFirst().orElseThrow();
        assertThat(user.get(1)).isEqualTo("user");
        assertThat(user.get(3)).isEqualTo(true);
        assertThat(user.get(4)).isEqualTo(100.0);
        // 새 컬럼 반영 + 테이블 설명 갱신
        assertThat(plan.doc().columns().get("donut_user")).hasSize(3);
        assertThat(user.get(2)).isEqualTo("회원 정보 v2");
        // 신규 테이블은 기본 도메인(stat)
        List<Object> brandNew = plan.doc().tables().stream()
                .filter(r -> "brand_new".equals(r.get(0))).findFirst().orElseThrow();
        assertThat(brandNew.get(1)).isEqualTo("stat");
    }

    @Test
    void merge_기존_관계를_보존한다() {
        ImportPlan plan = importService.plan(DDL, "merge");
        assertThat(plan.doc().relations())
                .anyMatch(r -> "legacy_table".equals(r.get(0)) && "donut_user".equals(r.get(1)));
    }

    @Test
    void replace_DDL에_없는_테이블은_제거된다() {
        ImportPlan plan = importService.plan(DDL, "replace");
        assertThat(plan.summary().removed()).containsExactly("legacy_table");
        assertThat(plan.doc().tables()).hasSize(2);
        // legacy_table 이 사라지면 관련 관계도 제거
        assertThat(plan.doc().relations())
                .noneMatch(r -> "legacy_table".equals(r.get(0)));
    }

    @Test
    void replace_도메인을_접두어_기준으로_재구성한다() {
        ImportPlan plan = importService.plan(DDL, "replace");
        // donut_user·brand_new 모두 접두어 그룹이 1개짜리 → '기타(etc)' 도메인으로 재구성
        assertThat(plan.doc().domains()).containsOnlyKeys("etc");
        assertThat(plan.doc().tables())
                .allMatch(r -> "etc".equals(r.get(1)));
        assertThat(plan.summary().newDomains()).containsExactly("etc");
    }

    @Test
    void replace_같은_접두어_2개_이상이면_도메인으로_승격한다() {
        String ddl = """
                CREATE TABLE `lms_user` (`user_no` int NOT NULL, PRIMARY KEY (`user_no`)) COMMENT='회원';
                CREATE TABLE `lms_order` (`order_no` int NOT NULL, PRIMARY KEY (`order_no`)) COMMENT='주문';
                CREATE TABLE `single_one` (`id` int NOT NULL, PRIMARY KEY (`id`)) COMMENT='단독';
                """;
        ImportPlan plan = importService.plan(ddl, "replace");
        assertThat(plan.doc().domains()).containsOnlyKeys("lms", "etc");
        List<Object> lmsUser = plan.doc().tables().stream()
                .filter(r -> "lms_user".equals(r.get(0))).findFirst().orElseThrow();
        assertThat(lmsUser.get(1)).isEqualTo("lms");
        List<Object> single = plan.doc().tables().stream()
                .filter(r -> "single_one".equals(r.get(0))).findFirst().orElseThrow();
        assertThat(single.get(1)).isEqualTo("etc");
    }

    @Test
    void merge_같은_접두어_신규_테이블은_새_도메인으로_묶인다() {
        String ddl = """
                CREATE TABLE `quiz_master` (`quiz_no` int NOT NULL, PRIMARY KEY (`quiz_no`)) COMMENT='퀴즈';
                CREATE TABLE `quiz_item` (`item_no` int NOT NULL, PRIMARY KEY (`item_no`)) COMMENT='문항';
                """;
        ImportPlan plan = importService.plan(ddl, "merge");
        assertThat(plan.doc().domains()).containsKey("quiz");
        assertThat(plan.summary().newDomains()).containsExactly("quiz");
        assertThat(plan.doc().tables())
                .filteredOn(r -> String.valueOf(r.get(0)).startsWith("quiz_"))
                .allMatch(r -> "quiz".equals(r.get(1)));
        // 기존 도메인은 그대로 유지
        assertThat(plan.doc().domains()).containsKeys("user", "stat");
    }

    @Test
    void 도메인명은_대표_테이블_코멘트의_한글을_사용한다() {
        String ddl = """
                CREATE TABLE `treatment` (`treat_no` int NOT NULL, PRIMARY KEY (`treat_no`)) COMMENT='진료 기록 (외래/입원)';
                CREATE TABLE `treatment_test` (`test_no` int NOT NULL, PRIMARY KEY (`test_no`)) COMMENT='검사';
                """;
        ImportPlan plan = importService.plan(ddl, "replace");
        // 대표 테이블(treatment) 코멘트에서 괄호 앞부분만 사용
        assertThat(plan.doc().domains().get("treatment").name()).isEqualTo("진료 기록");
    }

    @Test
    void 대표_테이블이_없으면_사전으로_한글_도메인명을_만든다() {
        String ddl = """
                CREATE TABLE `lms_user` (`user_no` int NOT NULL, PRIMARY KEY (`user_no`)) COMMENT='회원';
                CREATE TABLE `lms_order` (`order_no` int NOT NULL, PRIMARY KEY (`order_no`)) COMMENT='주문';
                """;
        ImportPlan plan = importService.plan(ddl, "replace");
        assertThat(plan.doc().domains().get("lms").name()).isEqualTo("학습관리");
    }

    @Test
    void merge_기존_테이블과_같은_접두어면_그_도메인을_따른다() {
        // 기존 donut_user 가 user 도메인 → 신규 donut_extra 도 user 도메인
        String ddl = """
                CREATE TABLE `donut_extra` (`extra_no` int NOT NULL, PRIMARY KEY (`extra_no`)) COMMENT='부가';
                """;
        ImportPlan plan = importService.plan(ddl, "merge");
        List<Object> extra = plan.doc().tables().stream()
                .filter(r -> "donut_extra".equals(r.get(0))).findFirst().orElseThrow();
        assertThat(extra.get(1)).isEqualTo("user");
        assertThat(plan.summary().newDomains()).isEmpty();
    }

    @Test
    void 같은_이름_컬럼의_기존_FK_플래그를_보존한다() {
        String ddl = """
                CREATE TABLE `legacy_table` (
                  `id` int(11) NOT NULL,
                  `user_no` int(11) NOT NULL COMMENT '회원번호',
                  PRIMARY KEY (`id`)
                ) COMMENT='구 테이블';
                """;
        ImportPlan plan = importService.plan(ddl, "merge");
        List<List<Object>> cols = plan.doc().columns().get("legacy_table");
        List<Object> userNo = cols.stream().filter(c -> "user_no".equals(c.get(0))).findFirst().orElseThrow();
        assertThat(userNo.get(3)).isEqualTo("FK");
    }
}
