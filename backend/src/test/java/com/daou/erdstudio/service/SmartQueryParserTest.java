package com.daou.erdstudio.service;

import com.daou.erdstudio.service.DdlParser.ParsedColumn;
import com.daou.erdstudio.service.DdlParser.ParsedSchema;
import com.daou.erdstudio.service.DdlParser.ParsedTable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmartQueryParserTest {

    private final SmartQueryParser parser = new SmartQueryParser(new ObjectMapper());

    @Test
    void MySQL_추출_결과를_파싱한다_불리언은_1과_0() {
        String json = """
                { "tables": [
                    { "name": "donut_user", "comment": "회원",
                      "columns": [
                        { "name": "user_no", "type": "int(11) unsigned", "comment": "회원번호", "pk": 1, "uk": 0 },
                        { "name": "email", "type": "varchar(100)", "comment": "", "pk": 0, "uk": 1 } ] },
                    { "name": "donut_order", "comment": "주문",
                      "columns": [
                        { "name": "order_no", "type": "bigint(20)", "comment": "", "pk": 1, "uk": 0 },
                        { "name": "user_no", "type": "int(11) unsigned", "comment": "", "pk": 0, "uk": 0 } ] } ],
                  "relations": [
                    { "child": "donut_order", "childCol": "user_no", "parent": "donut_user", "parentCol": "user_no" } ] }
                """;
        ParsedSchema schema = parser.parse(json);

        assertThat(schema.tables()).hasSize(2);
        ParsedTable user = schema.tables().get(0);
        assertThat(user.name()).isEqualTo("donut_user");
        assertThat(user.comment()).isEqualTo("회원");
        ParsedColumn userNo = user.columns().get(0);
        assertThat(userNo.colType()).isEqualTo("int uns");
        assertThat(userNo.flags()).containsExactly("PK");
        assertThat(user.columns().get(1).flags()).containsExactly("UK");

        assertThat(schema.relations()).hasSize(1);
        assertThat(schema.relations().get(0).child()).isEqualTo("donut_order");
        // 관계 컬럼에 FK 플래그가 붙는다
        assertThat(schema.tables().get(1).columns().get(1).flags()).contains("FK");
    }

    @Test
    void PostgreSQL_타입을_정규화한다() {
        String json = """
                { "tables": [
                    { "name": "users", "comment": "",
                      "columns": [
                        { "name": "id", "type": "integer", "comment": "", "pk": true },
                        { "name": "nick", "type": "character varying(50)", "comment": "" },
                        { "name": "joined_at", "type": "timestamp without time zone", "comment": "" } ] } ] }
                """;
        ParsedSchema schema = parser.parse(json);
        ParsedTable t = schema.tables().get(0);
        assertThat(t.columns().get(1).colType()).isEqualTo("varchar(50)");
        assertThat(t.columns().get(2).colType()).isEqualTo("timestamp");
    }

    @Test
    void 문자열로_한_번_감싼_JSON도_받아들인다() {
        String json = "\"{\\\"tables\\\":[{\\\"name\\\":\\\"t1\\\",\\\"columns\\\":[{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"int\\\"}]}]}\"";
        ParsedSchema schema = parser.parse(json);
        assertThat(schema.tables()).hasSize(1);
        assertThat(schema.tables().get(0).name()).isEqualTo("t1");
    }

    @Test
    void 비어_있거나_형식이_다르면_거부한다() {
        assertThatThrownBy(() -> parser.parse("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("not json")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 파싱 실패");
        assertThatThrownBy(() -> parser.parse("{\"tables\":[]}")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tables");
        assertThatThrownBy(() -> parser.parse("[1,2]")).isInstanceOf(IllegalArgumentException.class);
    }
}
