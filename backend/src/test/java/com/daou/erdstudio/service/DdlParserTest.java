package com.daou.erdstudio.service;

import com.daou.erdstudio.service.DdlParser.ParsedColumn;
import com.daou.erdstudio.service.DdlParser.ParsedSchema;
import com.daou.erdstudio.service.DdlParser.ParsedTable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DdlParserTest {

    private final DdlParser parser = new DdlParser();

    private static final String SAMPLE = """
            -- 주석은 무시된다
            CREATE TABLE `donut_user` (
              `user_no` int(11) NOT NULL AUTO_INCREMENT COMMENT '회원번호',
              `user_id` varchar(16) NOT NULL COMMENT '회원 아이디',
              `fail_cnt` int(11) unsigned DEFAULT 0 COMMENT '로그인 실패, 횟수',
              `amount` decimal(5,2) DEFAULT NULL,
              PRIMARY KEY (`user_no`),
              UNIQUE KEY `donut_user_idx1` (`user_id`),
              KEY `donut_user_idx2` (`fail_cnt`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COMMENT='회원 정보';

            CREATE TABLE `donut_bill` (
              `bill_no` int(11) NOT NULL AUTO_INCREMENT,
              `user_no` int(11) NOT NULL,
              PRIMARY KEY (`bill_no`),
              CONSTRAINT `fk_bill_user` FOREIGN KEY (`user_no`) REFERENCES `donut_user` (`user_no`)
            ) COMMENT='결제';

            ALTER TABLE `donut_bill` ADD CONSTRAINT `fk_dup` FOREIGN KEY (`user_no`) REFERENCES `donut_user` (`user_no`);
            """;

    @Test
    void 테이블과_컬럼_코멘트를_파싱한다() {
        ParsedSchema schema = parser.parse(SAMPLE);
        assertThat(schema.tables()).hasSize(2);
        ParsedTable user = schema.tables().get(0);
        assertThat(user.name()).isEqualTo("donut_user");
        assertThat(user.comment()).isEqualTo("회원 정보");
        assertThat(user.columns()).extracting(ParsedColumn::name)
                .containsExactly("user_no", "user_id", "fail_cnt", "amount");
        // 콤마가 든 코멘트도 잘린다
        assertThat(user.columns().get(2).comment()).isEqualTo("로그인 실패, 횟수");
    }

    @Test
    void 타입을_정규화한다() {
        ParsedSchema schema = parser.parse(SAMPLE);
        ParsedTable user = schema.tables().get(0);
        assertThat(user.columns().get(0).colType()).isEqualTo("int");
        assertThat(user.columns().get(1).colType()).isEqualTo("varchar(16)");
        assertThat(user.columns().get(2).colType()).isEqualTo("int uns");
        assertThat(user.columns().get(3).colType()).isEqualTo("decimal(5,2)");
    }

    @Test
    void PK_UK_FK_플래그를_붙인다() {
        ParsedSchema schema = parser.parse(SAMPLE);
        ParsedTable user = schema.tables().get(0);
        assertThat(user.columns().get(0).flags()).containsExactly("PK");
        assertThat(user.columns().get(1).flags()).containsExactly("UK");
        ParsedTable bill = schema.tables().get(1);
        assertThat(bill.columns().get(1).flags()).contains("FK");
    }

    @Test
    void FK_관계를_중복없이_추출한다() {
        ParsedSchema schema = parser.parse(SAMPLE);
        // inline FK + ALTER FK 가 같은 관계 → 1건으로 dedupe
        assertThat(schema.relations()).hasSize(1);
        assertThat(schema.relations().get(0).child()).isEqualTo("donut_bill");
        assertThat(schema.relations().get(0).parent()).isEqualTo("donut_user");
    }

    @Test
    void CREATE_TABLE_이_없으면_거부한다() {
        assertThatThrownBy(() -> parser.parse("SELECT 1;"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREATE TABLE");
        assertThatThrownBy(() -> parser.parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
