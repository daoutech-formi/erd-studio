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

    private static final String PG_SAMPLE = """
            CREATE TABLE public.users (
                user_no bigserial NOT NULL,
                email character varying(100) UNIQUE,
                score double precision,
                created_at timestamp with time zone DEFAULT now()
            );

            CREATE TABLE public.orders (
                order_no serial PRIMARY KEY,
                user_no bigint REFERENCES public.users (user_no),
                memo text
            );

            ALTER TABLE ONLY public.users
                ADD CONSTRAINT users_pkey PRIMARY KEY (user_no);
            ALTER TABLE ONLY public.orders
                ADD CONSTRAINT orders_user_fk FOREIGN KEY (user_no) REFERENCES public.users (user_no);

            COMMENT ON TABLE public.users IS '회원 정보';
            COMMENT ON COLUMN public.users.email IS '이메일';
            """;

    @Test
    void PostgreSQL_DDL_을_파싱한다() {
        ParsedSchema schema = parser.parse(PG_SAMPLE);
        assertThat(schema.tables()).extracting(ParsedTable::name).containsExactly("users", "orders");

        ParsedTable users = schema.tables().get(0);
        assertThat(users.comment()).isEqualTo("회원 정보");
        assertThat(users.columns()).extracting(ParsedColumn::colType)
                .containsExactly("bigint", "varchar(100)", "double", "timestamptz");
        assertThat(users.columns().get(0).flags()).containsExactly("PK"); // ALTER TABLE ONLY … PRIMARY KEY
        assertThat(users.columns().get(1).flags()).containsExactly("UK"); // 인라인 UNIQUE
        assertThat(users.columns().get(1).comment()).isEqualTo("이메일"); // COMMENT ON COLUMN

        ParsedTable orders = schema.tables().get(1);
        assertThat(orders.columns().get(0).flags()).containsExactly("PK"); // 인라인 PRIMARY KEY (serial)
        assertThat(orders.columns().get(1).flags()).contains("FK");

        // 인라인 REFERENCES + ALTER FK 가 같은 관계 → 1건으로 dedupe
        assertThat(schema.relations()).hasSize(1);
        assertThat(schema.relations().get(0).child()).isEqualTo("orders");
        assertThat(schema.relations().get(0).parent()).isEqualTo("users");
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
