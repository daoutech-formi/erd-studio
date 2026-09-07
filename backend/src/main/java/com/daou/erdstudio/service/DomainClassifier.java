package com.daou.erdstudio.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 테이블명 + COMMENT 로 도메인(토픽)을 판정한다. (키워드 사전 + 가중치)
 *
 * 설계 원칙 — 주체(엔티티) 우선, 성격어는 보조
 *  <ul>
 *    <li>ENTITY: 업무 주체 명사(회원·관리자·상품·주문…). 가장 강하다.
 *        '회원 옵션'은 옵션이 아니라 <b>회원</b>, '관리자 작업 이력'은 이력이 아니라 <b>관리자</b>.</li>
 *    <li>STRUCTURAL: 통계·로그·연동처럼 그 자체가 도메인이 되는 성격어.
 *        단, <b>테이블명에 신호가 있고 이름에 엔티티 신호가 없을 때만</b> 엔티티급으로 올라간다.
 *        (donut_monthly_stat → 통계 / ip_whitelist_history → 보안)</li>
 *    <li>WEAK: 옵션·설정·코드처럼 수식에 가까운 단어. 단독으로만 도메인이 된다.</li>
 *  </ul>
 * 점수는 도메인별 최대값을 쓴다(합산하면 단어 많은 도메인이 유리해져 왜곡됨).
 * 동점이면 사전 정의 순서가 앞선 도메인이 이긴다.
 *
 * 사내 서비스는 사용자 웹서비스와 어드민 웹페이지가 분리돼 있어,
 * 어드민/보안/운영 관련 테이블을 사용자 도메인과 별도로 분류한다.
 */
@Component
public class DomainClassifier {

    private enum Tier { ENTITY, STRUCTURAL, WEAK }

    private record Rule(String domain, Tier tier, Set<String> nameWords, Set<String> commentWords) {
    }

    // 점수표
    private static final int ENTITY_NAME = 100;
    private static final int ENTITY_COMMENT = 90;
    private static final int STRUCT_NAME_PROMOTED = 100;  // 엔티티 신호보다 앞에 등장 → 그 자체가 주체
    private static final int STRUCT_NAME_DEMOTED = 40;    // 엔티티 신호가 먼저 등장 → 수식어
    private static final int STRUCT_COMMENT = 45;
    private static final int WEAK_NAME = 15;
    private static final int WEAK_COMMENT = 12;

    /**
     * 도메인 사전. 위에 있을수록 동점 시 우선.
     * nameWords = 테이블명에서 찾을 토큰(영문/약어 중심), commentWords = 코멘트에서 찾을 단어(한글 중심).
     */
    private static final List<Rule> RULES = List.of(
            // ───────── 어드민/운영 (사용자 도메인보다 먼저 판정) ─────────
            new Rule("관리자", Tier.ENTITY,
                    Set.of("admin", "manager", "operator", "backoffice"),
                    Set.of("관리자", "어드민", "운영자", "백오피스")),
            new Rule("보안/접근제어", Tier.ENTITY,
                    Set.of("ip", "whitelist", "blacklist", "security", "acl", "secure", "otp"),
                    Set.of("보안", "차단", "허용", "예외", "접근제어", "화이트리스트", "블랙리스트", "아이피")),

            // ───────── 회원/인증 ─────────
            new Rule("회원/인증", Tier.ENTITY,
                    Set.of("user", "member", "customer", "auth", "login", "oauth", "account",
                            "kmc", "sleep", "quit", "join", "grade", "company", "biz"),
                    Set.of("회원", "가입", "탈퇴", "휴면", "인증", "로그인", "계정", "본인확인",
                            "간편로그인", "비밀번호", "등급", "기업정보", "사업자", "고객")),
            new Rule("조직/직원", Tier.ENTITY,
                    Set.of("dept", "department", "employee", "staff", "organization", "position", "hr"),
                    Set.of("조직", "부서", "사원", "직원", "임직원", "발령", "직급", "직위", "인사")),

            // ───────── 커머스 ─────────
            new Rule("포인트/적립금", Tier.ENTITY,
                    Set.of("point", "deposit", "mileage", "saving", "reward", "credit"),
                    Set.of("포인트", "적립금", "적립", "마일리지", "예치금", "캐시", "충전")),
            new Rule("쿠폰/상품권", Tier.ENTITY,
                    Set.of("coupon", "giftcard", "gift", "voucher", "pin"),
                    Set.of("쿠폰", "상품권", "기프트", "핀번호", "바우처", "교환권", "낙전")),
            new Rule("상품/브랜드", Tier.ENTITY,
                    Set.of("goods", "product", "brand", "category", "item", "stock", "inventory", "display"),
                    Set.of("상품", "브랜드", "카테고리", "재고", "전시", "진열", "품목")),
            new Rule("주문/배송", Tier.ENTITY,
                    Set.of("order", "cart", "delivery", "shipping", "claim", "invoice", "pick"),
                    Set.of("주문", "배송", "출고", "송장", "반품", "교환", "장바구니", "구매", "발주")),
            new Rule("결제/정산", Tier.ENTITY,
                    Set.of("bill", "billing", "pay", "payment", "pg", "kcp", "daoupay", "settle",
                            "settlement", "refund", "account", "vat", "tax"),
                    Set.of("결제", "수납", "청구", "환불", "정산", "매출", "세금계산서", "가상계좌",
                            "카드", "대사", "수수료", "거래액")),
            new Rule("프로모션/이벤트", Tier.ENTITY,
                    Set.of("event", "promotion", "roulette", "payback", "campaign", "recommend"),
                    Set.of("이벤트", "프로모션", "룰렛", "추천", "응모", "당첨", "페이백", "혜택", "기획전")),

            // ───────── 메시지/발송 ─────────
            new Rule("메시지/발송", Tier.ENTITY,
                    Set.of("msg", "message", "send", "sender", "sendphone", "alimtalk", "sms",
                            "lms", "mms", "push", "notification", "templet", "template", "uds"),
                    Set.of("발송", "메시지", "문자", "알림톡", "친구톡", "푸시", "수신", "발신번호",
                            "템플릿", "예약발송")),
            new Rule("메일", Tier.ENTITY,
                    Set.of("mail", "email", "smtp", "imap"),
                    Set.of("메일", "이메일", "메일함", "스팸")),
            new Rule("메신저/채팅", Tier.ENTITY,
                    Set.of("messenger", "chat", "room", "conversation", "emoticon"),
                    Set.of("메신저", "채팅", "대화방", "쪽지", "이모티콘")),

            // ───────── 그룹웨어 업무 ─────────
            new Rule("전자결재", Tier.ENTITY,
                    Set.of("approval", "approve", "draft", "sign", "workflow"),
                    Set.of("결재", "결재선", "기안", "승인", "반려", "합의", "문서양식")),
            new Rule("근태", Tier.ENTITY,
                    Set.of("attendance", "commute", "vacation", "leave", "overtime", "shift", "worktime"),
                    Set.of("근태", "출퇴근", "출근", "퇴근", "휴가", "연차", "근무", "초과근무", "지각")),
            new Rule("경리회계", Tier.ENTITY,
                    Set.of("accounting", "budget", "expense", "ledger", "slip", "erp"),
                    Set.of("회계", "경리", "전표", "계정과목", "예산", "지출", "매입", "법인카드", "증빙")),
            new Rule("게시판/문의", Tier.ENTITY,
                    Set.of("bbs", "board", "post", "comment", "notice", "faq", "qna", "inquiry",
                            "counsel", "review"),
                    Set.of("게시판", "게시글", "댓글", "공지", "문의", "상담", "리뷰", "후기")),
            new Rule("일정/예약", Tier.ENTITY,
                    Set.of("calendar", "schedule", "reservation", "booking", "appointment"),
                    Set.of("일정", "캘린더", "예약", "회의실", "자원예약")),
            new Rule("문서/파일", Tier.ENTITY,
                    Set.of("document", "file", "folder", "storage", "attach", "upload", "image"),
                    Set.of("문서", "파일", "첨부", "폴더", "저장소", "이미지")),
            new Rule("주소록", Tier.ENTITY,
                    Set.of("addr", "address", "contact"),
                    Set.of("주소록", "연락처")),

            // ───────── AI / 노코드 ─────────
            new Rule("AI", Tier.ENTITY,
                    Set.of("ai", "llm", "prompt", "embedding", "vector", "chatbot", "inference", "model"),
                    Set.of("인공지능", "프롬프트", "임베딩", "챗봇", "추론")),
            new Rule("앱빌더/노코드", Tier.ENTITY,
                    Set.of("nocode", "builder", "form", "widget", "layout", "canvas", "works"),
                    Set.of("노코드", "앱빌더", "위젯", "레이아웃", "커스텀앱", "폼빌더")),

            // ───────── 컨텐츠 ─────────
            new Rule("컨텐츠/배너", Tier.ENTITY,
                    Set.of("content", "banner", "popup", "theme", "curation"),
                    Set.of("컨텐츠", "배너", "팝업", "테마", "메인노출", "큐레이션")),

            // ───────── 구조적 성격 (이름에 신호가 있고 엔티티가 없을 때만 승격) ─────────
            new Rule("통계", Tier.STRUCTURAL,
                    Set.of("stat", "static", "statistics", "summary", "aggregate", "monthly", "daily"),
                    Set.of("통계", "집계", "현황", "스냅샷", "리포트", "지표", "실적")),
            new Rule("로그/이력", Tier.STRUCTURAL,
                    Set.of("log", "history", "hist", "audit", "tracking", "trace"),
                    Set.of("로그", "이력", "히스토리", "감사", "추적", "트래킹")),
            new Rule("연동/인터페이스", Tier.STRUCTURAL,
                    Set.of("sync", "interface", "webhook", "outbound", "inbound", "bridge", "relay"),
                    Set.of("연동", "인터페이스", "동기화", "웹훅", "아웃바운드", "인바운드")),
            new Rule("배치/스케줄", Tier.STRUCTURAL,
                    Set.of("batch", "scheduler", "queue"),
                    Set.of("배치", "스케줄러", "큐", "적재")),

            // ───────── 약한 성격어 (단독일 때만 도메인) ─────────
            new Rule("코드/설정", Tier.WEAK,
                    Set.of("code", "config", "option", "policy", "preference", "property", "setting"),
                    Set.of("공통코드", "코드", "환경설정", "옵션", "정책", "설정")));

    /** 도메인 후보 — 동점 판정을 위해 코멘트 근거와 이름 내 등장 위치를 함께 든다. */
    private record Candidate(String domain, int score, boolean commentSupport, int namePos, int order) {
    }

    /**
     * 코멘트+테이블명 기준 도메인 판정. 매칭이 없으면 null.
     * 동점이면 ① 코멘트 근거가 있는 쪽 ② 이름에서 먼저 등장한 쪽(앞 토큰이 주체)
     * ③ 사전 정의 순서로 가른다. (giftcard_auth_log → 인증이 아니라 상품권,
     * bill_saving_history → 코멘트가 '적립'을 말하므로 결제가 아니라 적립금)
     */
    public String classify(String tableName, String comment) {
        String nameText = normalize(tableName);
        String commentText = normalize(comment);

        // 이름에서 가장 앞서 등장하는 엔티티 토큰 위치 — 구조적 성격어의 승격/강등 기준.
        // 엔티티가 먼저면 성격어는 수식(ip_whitelist_history → 보안),
        // 성격어가 먼저면 그 자체가 주체(donut_static_point_status → 통계).
        int entityPos = Integer.MAX_VALUE;
        for (Rule rule : RULES) {
            if (rule.tier() == Tier.ENTITY) {
                int p = earliestMatch(nameText, rule.nameWords());
                if (p >= 0 && p < entityPos) {
                    entityPos = p;
                }
            }
        }

        Map<String, Candidate> byDomain = new LinkedHashMap<>();
        int order = 0;
        for (Rule rule : RULES) {
            int namePos = earliestMatch(nameText, rule.nameWords());
            boolean nameHit = namePos >= 0;
            boolean commentHit = matchesAny(commentText, rule.commentWords())
                    || matchesAny(commentText, rule.nameWords());
            int best = 0;
            switch (rule.tier()) {
                case ENTITY -> {
                    if (nameHit) {
                        best = ENTITY_NAME;
                    } else if (commentHit) {
                        best = ENTITY_COMMENT;
                    }
                }
                case STRUCTURAL -> {
                    if (nameHit) {
                        best = entityPos < namePos ? STRUCT_NAME_DEMOTED : STRUCT_NAME_PROMOTED;
                    }
                    if (commentHit) {
                        best = Math.max(best, STRUCT_COMMENT);
                    }
                }
                case WEAK -> {
                    if (nameHit) {
                        best = WEAK_NAME;
                    } else if (commentHit) {
                        best = WEAK_COMMENT;
                    }
                }
            }
            if (best > 0) {
                Candidate next = new Candidate(rule.domain(), best, commentHit,
                        nameHit ? namePos : Integer.MAX_VALUE, order);
                byDomain.merge(rule.domain(), next, (a, b) -> better(a, b) == a ? a : b);
            }
            order++;
        }
        return byDomain.values().stream()
                .reduce((a, b) -> better(a, b))
                .map(Candidate::domain)
                .orElse(null);
    }

    /** 점수 → 코멘트 근거 → 이름 내 위치 → 사전 정의 순서로 우열을 가린다. */
    private static Candidate better(Candidate a, Candidate b) {
        if (a.score() != b.score()) {
            return a.score() > b.score() ? a : b;
        }
        if (a.commentSupport() != b.commentSupport()) {
            return a.commentSupport() ? a : b;
        }
        if (a.namePos() != b.namePos()) {
            return a.namePos() < b.namePos() ? a : b;
        }
        return a.order() <= b.order() ? a : b;
    }

    /** 사전에 정의된 도메인 표시명 목록 (정의 순서 = 우선순위). */
    public List<String> knownDomains() {
        List<String> out = new ArrayList<>();
        for (Rule r : RULES) {
            if (!out.contains(r.domain())) {
                out.add(r.domain());
            }
        }
        return out;
    }

    /** 사전 정의 순서 — 정렬 시 동점 처리용. */
    public int priorityOf(String domain) {
        int idx = knownDomains().indexOf(domain);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private boolean matchesAny(String text, Set<String> words) {
        return earliestMatch(text, words) >= 0;
    }

    /** 단어 집합 중 텍스트에서 가장 앞서 매칭되는 위치. 없으면 -1. */
    private int earliestMatch(String text, Set<String> words) {
        int best = -1;
        for (String w : words) {
            int idx = indexOfWord(text, w);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    /**
     * 단어 포함 검사(위치 반환). 영문은 토큰 경계를 확인해 부분일치 오탐(예: 'log' in 'login',
     * 'ip' in 'description')을 막고, 한글은 부분 문자열로 본다(복합명사 대응). 없으면 -1.
     */
    private int indexOfWord(String text, String word) {
        if (text.isEmpty() || !text.contains(word)) {
            return -1;
        }
        if (word.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3)) {
            return text.indexOf(word);
        }
        int from = 0;
        while (true) {
            int idx = text.indexOf(word, from);
            if (idx < 0) {
                return -1;
            }
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int end = idx + word.length();
            boolean rightOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (leftOk && rightOk) {
                return idx;
            }
            from = idx + 1;
        }
    }
}
