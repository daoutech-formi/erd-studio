package com.daou.erdstudio.service;

import com.daou.erdstudio.domain.ErdRoom;
import com.daou.erdstudio.repository.ErdRoomRepository;
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
    @Autowired
    ErdRoomRepository roomRepository;

    private Long roomId;

    @BeforeEach
    void seed() {
        ErdRoom room = roomRepository.save(new ErdRoom("t_ddl_room", "tester"));
        roomId = room.getId();
        Map<String, SchemaDoc.DomainDef> domains = new LinkedHashMap<>();
        domains.put("user", new SchemaDoc.DomainDef("회원/인증", "#4f8cff"));
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
        schemaService.replaceAll(roomId, doc);
    }

    private String domainOf(ImportPlan plan, String tableName) {
        return plan.doc().tables().stream()
                .filter(r -> tableName.equals(r.get(0)))
                .map(r -> String.valueOf(r.get(1)))
                .findFirst().orElseThrow();
    }

    private String domainKeyByName(ImportPlan plan, String domainName) {
        return plan.doc().domains().entrySet().stream()
                .filter(e -> e.getValue().name().equals(domainName))
                .map(Map.Entry::getKey)
                .findFirst().orElseThrow();
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
        ImportPlan plan = importService.plan(roomId, DDL, "merge");
        assertThat(plan.summary().added()).containsExactly("brand_new");
        assertThat(plan.summary().updated()).containsExactly("donut_user");
        assertThat(plan.summary().removed()).isEmpty();
        assertThat(plan.doc().tables()).hasSize(3);
        // donut_user 의 위치·도메인·허브 보존
        List<Object> user = plan.doc().tables().stream()
                .filter(r -> "donut_user".equals(r.get(0))).findFirst().orElseThrow();
        assertThat(user.get(1)).isEqualTo("user");
        assertThat(user.get(3)).isEqualTo(true);
        assertThat(user.get(4)).isEqualTo(100.0);
        assertThat(plan.doc().columns().get("donut_user")).hasSize(3);
        assertThat(user.get(2)).isEqualTo("회원 정보 v2");
        // brand_new 는 사전이 '상품/브랜드'로 확정 → 1개여도 새 도메인으로 승격된다
        assertThat(plan.doc().domains().get(domainOf(plan, "brand_new")).name())
                .isEqualTo("상품/브랜드");
        assertThat(plan.summary().newDomains()).contains("상품/브랜드");
    }

    @Test
    void merge_기존_관계를_보존한다() {
        ImportPlan plan = importService.plan(roomId, DDL, "merge");
        assertThat(plan.doc().relations())
                .anyMatch(r -> "legacy_table".equals(r.get(0)) && "donut_user".equals(r.get(1)));
    }

    @Test
    void replace_DDL에_없는_테이블은_제거되고_사전_도메인으로_재구성된다() {
        ImportPlan plan = importService.plan(roomId, DDL, "replace");
        assertThat(plan.summary().removed()).containsExactly("legacy_table");
        assertThat(plan.doc().tables()).hasSize(2);
        assertThat(plan.doc().relations())
                .noneMatch(r -> "legacy_table".equals(r.get(0)));
        // 사전이 확정한 도메인은 1개여도 유지된다
        assertThat(plan.doc().domains().values())
                .extracting(SchemaDoc.DomainDef::name)
                .contains("회원/인증", "상품/브랜드");
        assertThat(plan.doc().tables()).allMatch(r -> r.get(1) != null);
    }

    @Test
    void 키워드_사전으로_도메인을_자동_생성한다() {
        String ddl = """
                CREATE TABLE `member_base` (`m_no` int NOT NULL, PRIMARY KEY (`m_no`)) COMMENT='회원 정보';
                CREATE TABLE `member_login` (`l_no` int NOT NULL, PRIMARY KEY (`l_no`)) COMMENT='회원 로그인 계정';
                CREATE TABLE `pay_main` (`p_no` int NOT NULL, PRIMARY KEY (`p_no`)) COMMENT='결제 내역';
                CREATE TABLE `pay_cancel` (`c_no` int NOT NULL, PRIMARY KEY (`c_no`)) COMMENT='결제 환불';
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "replace");
        // 사전 도메인명으로 생성된다
        assertThat(plan.doc().domains().values())
                .extracting(SchemaDoc.DomainDef::name)
                .contains("회원/인증", "결제/정산");
        assertThat(domainOf(plan, "member_base")).isEqualTo(domainKeyByName(plan, "회원/인증"));
        assertThat(domainOf(plan, "member_login")).isEqualTo(domainKeyByName(plan, "회원/인증"));
        assertThat(domainOf(plan, "pay_main")).isEqualTo(domainKeyByName(plan, "결제/정산"));
        assertThat(plan.summary().newDomains()).contains("회원/인증", "결제/정산");
    }

    @Test
    void 통계_테이블은_통계_도메인으로_모인다() {
        String ddl = """
                CREATE TABLE `donut_monthly_stat` (`s_no` int NOT NULL, PRIMARY KEY (`s_no`)) COMMENT='회원 월별 스냅샷 통계 정보';
                CREATE TABLE `donut_static_sales_status` (`d` char(8) NOT NULL, PRIMARY KEY (`d`)) COMMENT='판매현황 통계';
                CREATE TABLE `donut_static_point_status` (`p` char(8) NOT NULL, PRIMARY KEY (`p`)) COMMENT='포인트 적립 현황 통계';
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "replace");
        String statKey = domainKeyByName(plan, "통계");
        assertThat(domainOf(plan, "donut_monthly_stat")).isEqualTo(statKey);
        assertThat(domainOf(plan, "donut_static_sales_status")).isEqualTo(statKey);
        assertThat(domainOf(plan, "donut_static_point_status")).isEqualTo(statKey);
    }

    @Test
    void 도메인은_최대_12개로_제한된다() {
        StringBuilder ddl = new StringBuilder();
        // 서로 다른 토픽 20종 x 2개 = 40 테이블
        String[] comments = {"회원 정보", "결제 내역", "주문 배송", "상품 브랜드", "쿠폰 상품권",
                "포인트 적립금", "메시지 발송", "메일함", "메신저 채팅", "전자결재 기안",
                "근태 출퇴근", "회계 전표", "게시판 공지", "일정 예약", "문서 파일",
                "주소록 연락처", "AI 프롬프트", "노코드 폼", "통계 집계", "로그 이력"};
        for (int i = 0; i < comments.length; i++) {
            for (int j = 0; j < 2; j++) {
                ddl.append(String.format(
                        "CREATE TABLE `t%d_%d` (`id%d_%d` int NOT NULL, PRIMARY KEY (`id%d_%d`)) COMMENT='%s';%n",
                        i, j, i, j, i, j, comments[i]));
            }
        }
        ImportPlan plan = importService.plan(roomId, ddl.toString(), "replace");
        assertThat(plan.doc().domains()).hasSizeLessThanOrEqualTo(12);
        // 초과분은 기타로 병합되어 미배치 테이블이 없다
        assertThat(plan.doc().tables()).allMatch(r -> r.get(1) != null);
    }

    @Test
    void 코멘트가_없어도_테이블명_키워드로_분류한다() {
        String ddl = """
                CREATE TABLE `roulette_master` (`r_no` int NOT NULL, PRIMARY KEY (`r_no`));
                CREATE TABLE `roulette_apply` (`a_no` int NOT NULL, PRIMARY KEY (`a_no`));
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "replace");
        // 테이블명 roulette → 사전의 프로모션/이벤트
        String key = domainKeyByName(plan, "프로모션/이벤트");
        assertThat(domainOf(plan, "roulette_master")).isEqualTo(key);
        assertThat(domainOf(plan, "roulette_apply")).isEqualTo(key);
    }

    @Test
    void 일반어뿐인_코멘트는_테이블명으로_분류한다() {
        String ddl = """
                CREATE TABLE `coupon_master` (`c_no` int NOT NULL, PRIMARY KEY (`c_no`)) COMMENT='정보 관리';
                CREATE TABLE `coupon_issue` (`i_no` int NOT NULL, PRIMARY KEY (`i_no`)) COMMENT='내역';
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "replace");
        // 코멘트는 일반어뿐이지만 테이블명 coupon 이 사전에 매칭된다
        assertThat(plan.doc().domains().values())
                .extracting(SchemaDoc.DomainDef::name)
                .contains("쿠폰/상품권");
    }

    @Test
    void 사전에_없는_토픽은_테이블명_토큰으로_분류한다() {
        String ddl = """
                CREATE TABLE `widgetx_conf` (`w_no` int NOT NULL, PRIMARY KEY (`w_no`)) COMMENT='';
                CREATE TABLE `widgetx_data` (`d_no` int NOT NULL, PRIMARY KEY (`d_no`)) COMMENT='';
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "replace");
        assertThat(plan.doc().domains().values())
                .extracting(SchemaDoc.DomainDef::name)
                .contains("widgetx");
    }

    @Test
    void 단독_테이블은_FK_이웃_도메인에_흡수된다() {
        String ddl = """
                CREATE TABLE `billing` (
                  `billing_no` int NOT NULL, PRIMARY KEY (`billing_no`)
                ) COMMENT='수납';
                CREATE TABLE `billing_item` (
                  `item_no` int NOT NULL,
                  `billing_no` int NOT NULL,
                  PRIMARY KEY (`item_no`),
                  CONSTRAINT `fk_i_b` FOREIGN KEY (`billing_no`) REFERENCES `billing` (`billing_no`)
                ) COMMENT='수납 항목';
                CREATE TABLE `receipt_print` (
                  `print_no` int NOT NULL,
                  `billing_no` int NOT NULL,
                  PRIMARY KEY (`print_no`),
                  CONSTRAINT `fk_r_b` FOREIGN KEY (`billing_no`) REFERENCES `billing` (`billing_no`)
                ) COMMENT='영수증 출력';
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "replace");
        String billingDomain = domainOf(plan, "billing");
        // 영수증(단독 토픽)은 FK 이웃인 수납(결제) 도메인으로 흡수
        assertThat(domainOf(plan, "receipt_print")).isEqualTo(billingDomain);
        // 사전이 billing·'수납'을 결제/정산으로 확정한다 (LLM 없이 도는 기본 경로 기준)
        assertThat(plan.doc().domains().get(billingDomain).name()).isEqualTo("결제/정산");
    }

    @Test
    void merge_토픽이_기존_도메인명과_겹치면_그_도메인을_따른다() {
        String ddl = """
                CREATE TABLE `extra_member` (`e_no` int NOT NULL, PRIMARY KEY (`e_no`)) COMMENT='회원 부가';
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "merge");
        // 토픽 '회원' ↔ 기존 도메인명 '회원/인증' 매칭
        assertThat(domainOf(plan, "extra_member")).isEqualTo("user");
        assertThat(plan.summary().newDomains()).isEmpty();
    }

    @Test
    void FK가_없으면_컬럼명으로_관계를_추론한다() {
        String ddl = """
                CREATE TABLE `donut_user` (
                  `user_no` int NOT NULL COMMENT '회원번호',
                  PRIMARY KEY (`user_no`)
                ) COMMENT='회원';
                CREATE TABLE `donut_goods` (
                  `goods_no` int NOT NULL COMMENT '상품번호',
                  PRIMARY KEY (`goods_no`)
                ) COMMENT='상품';
                CREATE TABLE `donut_cart` (
                  `cart_no` int NOT NULL,
                  `user_no` int NOT NULL COMMENT '회원번호',
                  `goods_no` int NOT NULL COMMENT '상품번호',
                  PRIMARY KEY (`cart_no`)
                ) COMMENT='장바구니';
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "replace");
        assertThat(plan.doc().relations())
                .anyMatch(r -> "donut_cart".equals(r.get(0)) && "donut_user".equals(r.get(1)))
                .anyMatch(r -> "donut_cart".equals(r.get(0)) && "donut_goods".equals(r.get(1)));
        // 추론된 참조 컬럼에는 FK 플래그가 붙는다
        assertThat(plan.doc().columns().get("donut_cart"))
                .anyMatch(c -> "user_no".equals(c.get(0)) && String.valueOf(c.get(3)).contains("FK"));
    }

    @Test
    void 접미사_컬럼은_라벨을_붙여_관계를_추론한다() {
        String ddl = """
                CREATE TABLE `donut_goods` (
                  `goods_no` int NOT NULL, PRIMARY KEY (`goods_no`)
                ) COMMENT='상품';
                CREATE TABLE `donut_event_goods` (
                  `content_no` int NOT NULL,
                  `b2c_goods_no` int DEFAULT NULL COMMENT 'B2C 상품번호',
                  PRIMARY KEY (`content_no`)
                ) COMMENT='상품 이벤트';
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "replace");
        assertThat(plan.doc().relations())
                .anyMatch(r -> "donut_event_goods".equals(r.get(0)) && "donut_goods".equals(r.get(1))
                        && r.size() > 2 && "b2c_goods_no".equals(r.get(2)));
    }

    @Test
    void 자기_PK가_다른_테이블을_참조하는_경우도_추론한다() {
        String ddl = """
                CREATE TABLE `donut_content` (
                  `content_no` int NOT NULL, PRIMARY KEY (`content_no`)
                ) COMMENT='컨텐츠';
                CREATE TABLE `donut_event_goods` (
                  `content_no` int NOT NULL COMMENT '컨텐츠번호',
                  PRIMARY KEY (`content_no`)
                ) COMMENT='상품 이벤트';
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "replace");
        assertThat(plan.doc().relations())
                .anyMatch(r -> "donut_event_goods".equals(r.get(0)) && "donut_content".equals(r.get(1)));
    }

    @Test
    void 모호한_generic_PK명은_잘못_연결하지_않는다() {
        String ddl = """
                CREATE TABLE `donut_point_history` (
                  `history_no` int NOT NULL, PRIMARY KEY (`history_no`)
                ) COMMENT='포인트 내역';
                CREATE TABLE `kmc_auth_history` (
                  `history_no` int NOT NULL, PRIMARY KEY (`history_no`)
                ) COMMENT='인증 이력';
                CREATE TABLE `donut_point_use` (
                  `use_no` int NOT NULL,
                  `history_no` int NOT NULL COMMENT '변동내역 번호',
                  PRIMARY KEY (`use_no`)
                ) COMMENT='포인트 사용';
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "replace");
        assertThat(plan.doc().relations())
                .anyMatch(r -> "donut_point_use".equals(r.get(0)) && "donut_point_history".equals(r.get(1)));
        assertThat(plan.doc().relations())
                .noneMatch(r -> "donut_point_history".equals(r.get(0)) && "kmc_auth_history".equals(r.get(1)))
                .noneMatch(r -> "kmc_auth_history".equals(r.get(0)) && "donut_point_history".equals(r.get(1)));
    }

    @Test
    void 대문자_스키마도_컬럼명으로_관계를_추론한다() {
        String ddl = """
                CREATE TABLE `USER_MAIN` (
                  `USER_NO` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '유저 번호',
                  PRIMARY KEY (`USER_NO`)
                ) COMMENT='유저 정보';
                CREATE TABLE `ADDRESS_BOOK_GROUP` (
                  `ADDRESS_BOOK_GROUP_NO` bigint(20) unsigned NOT NULL COMMENT '주소록 그룹 번호',
                  `USER_NO` bigint(20) unsigned NOT NULL COMMENT '유저 번호',
                  PRIMARY KEY (`ADDRESS_BOOK_GROUP_NO`)
                ) COMMENT='주소록 그룹';
                CREATE TABLE `ADDRESS_BOOK` (
                  `ADDRESS_BOOK_NO` bigint(20) unsigned NOT NULL COMMENT '주소록 번호',
                  `ADDRESS_BOOK_GROUP_NO` bigint(20) unsigned NOT NULL COMMENT '주소록 그룹 번호',
                  `USER_NO` bigint(20) unsigned NOT NULL COMMENT '유저 번호',
                  PRIMARY KEY (`ADDRESS_BOOK_NO`)
                ) COMMENT='주소록';
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "replace");
        assertThat(plan.doc().relations())
                .anyMatch(r -> "ADDRESS_BOOK".equals(r.get(0)) && "ADDRESS_BOOK_GROUP".equals(r.get(1)))
                .anyMatch(r -> "ADDRESS_BOOK".equals(r.get(0)) && "USER_MAIN".equals(r.get(1)))
                .anyMatch(r -> "ADDRESS_BOOK_GROUP".equals(r.get(0)) && "USER_MAIN".equals(r.get(1)));
    }

    @Test
    void FK제약이_있으면_그대로_사용한다() {
        String ddl = """
                CREATE TABLE `donut_user` (
                  `user_no` int NOT NULL, PRIMARY KEY (`user_no`)
                ) COMMENT='회원';
                CREATE TABLE `donut_bill` (
                  `bill_no` int NOT NULL,
                  `user_no` int NOT NULL,
                  PRIMARY KEY (`bill_no`),
                  CONSTRAINT `fk_b_u` FOREIGN KEY (`user_no`) REFERENCES `donut_user` (`user_no`)
                ) COMMENT='결제';
                """;
        ImportPlan plan = importService.plan(roomId, ddl, "replace");
        long count = plan.doc().relations().stream()
                .filter(r -> "donut_bill".equals(r.get(0)) && "donut_user".equals(r.get(1)))
                .count();
        assertThat(count).isEqualTo(1);
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
        ImportPlan plan = importService.plan(roomId, ddl, "merge");
        List<List<Object>> cols = plan.doc().columns().get("legacy_table");
        List<Object> userNo = cols.stream().filter(c -> "user_no".equals(c.get(0))).findFirst().orElseThrow();
        assertThat(String.valueOf(userNo.get(3))).contains("FK");
    }
}
