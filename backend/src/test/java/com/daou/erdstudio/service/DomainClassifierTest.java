package com.daou.erdstudio.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainClassifierTest {

    private final DomainClassifier classifier = new DomainClassifier();

    // ───────── 주체(엔티티) 우선 원칙 ─────────

    @Test
    void 수식어보다_주체를_우선한다() {
        // '옵션'(약한 성격어)이 아니라 '회원'(주체)으로
        assertThat(classifier.classify("donut_user_option", "회원 옵션")).isEqualTo("회원/인증");
        assertThat(classifier.classify("donut_user_option_history", "옵션 설정 이력 정보")).isEqualTo("회원/인증");
        // '이력'이 아니라 '관리자'로
        assertThat(classifier.classify("donut_admin_work_history", "어드민 작업 이력 관리")).isEqualTo("관리자");
        assertThat(classifier.classify("donut_admin_option", "관리자 옵션")).isEqualTo("관리자");
        assertThat(classifier.classify("donut_sendphone_history", "발신번호 인증 정보 이력")).isEqualTo("메시지/발송");
    }

    @Test
    void 관리자와_보안을_구분한다() {
        assertThat(classifier.classify("donut_admin", "관리자 정보")).isEqualTo("관리자");
        assertThat(classifier.classify("ip_blacklist", "IP 차단 관리")).isEqualTo("보안/접근제어");
        assertThat(classifier.classify("ip_whitelist", "예외 IP 관리")).isEqualTo("보안/접근제어");
        // 이력 성격어가 있어도 이름의 엔티티(ip/whitelist)가 이긴다
        assertThat(classifier.classify("ip_whitelist_history", "예외 IP 이력 관리")).isEqualTo("보안/접근제어");
    }

    // ───────── 구조적 성격어 (이름 신호 + 엔티티 없음 → 승격) ─────────

    @Test
    void 통계_테이블은_통계로_모인다() {
        assertThat(classifier.classify("donut_monthly_stat", "회원 월별 스냅샷 통계 정보")).isEqualTo("통계");
        assertThat(classifier.classify("donut_static_sales_status", "판매현황 통계")).isEqualTo("통계");
        assertThat(classifier.classify("donut_static_point_status", "포인트 적립 현황 통계")).isEqualTo("통계");
        assertThat(classifier.classify("static_trade_sales", "신규/기존 거래액 일별 통계")).isEqualTo("통계");
    }

    @Test
    void 순수_로그_테이블은_로그로_분류한다() {
        assertThat(classifier.classify("uds_log_202604", "")).isNotNull();
        assertThat(classifier.classify("donut_crm_tracking", "CRM 트래킹")).isEqualTo("로그/이력");
    }

    @Test
    void 영문_부분일치_오탐을_피한다() {
        // 'login' 안의 'log' 로 로그/이력이 되지 않아야 한다
        assertThat(classifier.classify("donut_auto_login", "자동 로그인 토큰")).isEqualTo("회원/인증");
    }

    // ───────── 커머스 ─────────

    @Test
    void 커머스_도메인을_분류한다() {
        assertThat(classifier.classify("donut_goods", "상품 정보")).isEqualTo("상품/브랜드");
        assertThat(classifier.classify("donut_brand", "상품 브랜드 정보")).isEqualTo("상품/브랜드");
        assertThat(classifier.classify("donut_order", "주문 내역")).isEqualTo("주문/배송");
        assertThat(classifier.classify("donut_cart", "장바구니 정보")).isEqualTo("주문/배송");
        assertThat(classifier.classify("donut_bill", "결제 내역")).isEqualTo("결제/정산");
        assertThat(classifier.classify("donut_bill_pg", "PG 결제 정보")).isEqualTo("결제/정산");
    }

    @Test
    void 적립금은_결제보다_우선한다() {
        // bill_ 접두어지만 적립 성격 → 포인트/적립금
        assertThat(classifier.classify("bill_saving_history", "구매적립정보 상세내역")).isEqualTo("포인트/적립금");
        assertThat(classifier.classify("donut_point", "회원 도넛포인트 정보")).isEqualTo("포인트/적립금");
        assertThat(classifier.classify("donut_deposit", "회원 적립금 정보")).isEqualTo("포인트/적립금");
    }

    @Test
    void 쿠폰과_이벤트를_분류한다() {
        assertThat(classifier.classify("giftcard_auth_log", "상품권 구매 인증 이력")).isEqualTo("쿠폰/상품권");
        assertThat(classifier.classify("expired_coupon_income", "낙전 수익")).isEqualTo("쿠폰/상품권");
        assertThat(classifier.classify("donut_roulette", "룰렛 정보")).isEqualTo("프로모션/이벤트");
        assertThat(classifier.classify("event_payback_apply", "구매 이벤트 지급내역")).isEqualTo("프로모션/이벤트");
    }

    // ───────── 메시지 / 그룹웨어 / AI ─────────

    @Test
    void 메시지_메일_메신저를_구분한다() {
        assertThat(classifier.classify("donut_sendphone", "발신번호 인증 정보")).isEqualTo("메시지/발송");
        assertThat(classifier.classify("msg_alimtalk_template", "알림톡 템플릿")).isEqualTo("메시지/발송");
        assertThat(classifier.classify("mail_box", "메일함 정보")).isEqualTo("메일");
        assertThat(classifier.classify("messenger_room", "메신저 대화방")).isEqualTo("메신저/채팅");
    }

    @Test
    void 그룹웨어_업무_도메인을_분류한다() {
        assertThat(classifier.classify("gw_approval_doc", "전자결재 기안 문서")).isEqualTo("전자결재");
        assertThat(classifier.classify("gw_attendance", "근태 출퇴근 기록")).isEqualTo("근태");
        assertThat(classifier.classify("acc_slip", "회계 전표")).isEqualTo("경리회계");
        assertThat(classifier.classify("gw_department", "부서 조직 정보")).isEqualTo("조직/직원");
    }

    @Test
    void AI_와_노코드_도메인을_분류한다() {
        assertThat(classifier.classify("ai_prompt", "AI 프롬프트 템플릿")).isEqualTo("AI");
        assertThat(classifier.classify("works_form", "노코드 폼 정의")).isEqualTo("앱빌더/노코드");
    }

    @Test
    void 게시판과_상담을_분류한다() {
        assertThat(classifier.classify("donut_bbs", "공지사항, FAQ 정보")).isEqualTo("게시판/문의");
        assertThat(classifier.classify("donut_qna", "Q&A 정보")).isEqualTo("게시판/문의");
        assertThat(classifier.classify("donut_counsel_bulk", "대량구매 상담 정보")).isEqualTo("게시판/문의");
        assertThat(classifier.classify("donut_addr_group", "회원 주소록 (그룹)")).isEqualTo("주소록");
    }

    @Test
    void 사전에_없으면_null을_반환한다() {
        assertThat(classifier.classify("zzz_unknown", "")).isNull();
        assertThat(classifier.classify("mystery", "무언가")).isNull();
    }
}
