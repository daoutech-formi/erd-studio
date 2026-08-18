# 실행 프롬프트: 메모–테이블 연결 및 클릭 하이라이트

> 이 문서는 AI 코딩 에이전트가 그대로 실행하는 프롬프트다.

## 1. 요구사항

**목적:** 캔버스 메모를 특정 테이블들과 연결해, 메모 클릭 한 번으로 그 메모가 어떤 테이블·관계에 대한 고민인지 시각적으로 드러나게 한다.

**동작 명세:**
- 입력:
  - 메모 편집 모달에서 사용자가 연결할 테이블을 0~10개 선택/해제한다.
  - 조회 모드(편집 모드 아님)에서 사용자가 메모를 클릭한다.
  - 사용자가 테이블 노드를 클릭해 선택한다.
- 처리:
  - 메모 행 포맷을 `[id, text, x, y, color, links]`로 확장한다. `links`는 테이블명 문자열 배열이며, 연결이 없으면 생략 가능(기존 문서·이력 스냅샷 하위호환).
  - 조회 모드에서 연결(links 길이 ≥ 1)이 있는 메모를 클릭하면 그 메모가 "활성 메모"로 선택된다. 같은 메모를 다시 클릭하거나 캔버스 배경을 클릭하면 해제된다.
  - 테이블 이름 변경(table.apply) 시 모든 메모의 links에서 옛 이름을 새 이름으로 치환하고, 테이블 삭제(table.delete) 시 links에서 그 이름을 제거한다(프론트 reducer·백엔드 DB 모두).
- 출력:
  - 활성 메모 상태: 클릭한 메모는 dim 해제 + 강조 테두리 표시, links에 포함된 테이블 노드는 dim=false, 그 외 모든 테이블·메모는 dim=true. links에 포함된 테이블 쌍 사이에 존재하는 관계선은 `hl` 클래스로 강조, 그 외 관계선은 dim.
  - 테이블 선택 상태: 선택된 테이블명을 links에 포함한 메모는 dim=false 유지(현재는 모든 메모가 흐려짐).
  - 평상시: links 길이 ≥ 1인 메모의 우상단에 링크 배지(클립 모양 SVG 아이콘 + 연결 개수 숫자)를 상시 표시한다.

**수용 기준:**
- [ ] 메모 편집 모달에서 테이블 2개를 연결 후 저장하면, 같은 방에 접속한 다른 브라우저 탭에서도 해당 메모에 배지 "2"가 표시된다.
- [ ] 조회 모드에서 그 메모 클릭 시: 연결 테이블 2개의 `.node`에 `dim` 클래스가 없고, 나머지 테이블에는 `dim`이 있으며, 두 테이블 사이 관계선 `g.edge`에 `hl` 클래스가 붙는다.
- [ ] 같은 메모 재클릭 또는 배경 클릭 시 모든 `dim`/`hl`이 클릭 전 상태로 돌아간다.
- [ ] links에 포함된 테이블을 클릭 선택하면 그 메모의 `.memo-node`에 `dim` 클래스가 붙지 않는다.
- [ ] 연결된 테이블 이름을 변경하면 메모 배지 개수가 유지되고, 새 이름 클릭 시 여전히 그 메모가 강조 대상에 포함된다. 페이지 새로고침 후에도 동일하다.
- [ ] 연결된 테이블을 삭제하면 메모의 links에서 제거되어 배지 개수가 1 감소한다. 페이지 새로고침 후에도 동일하다.
- [ ] 링크 편집 후 Undo(Ctrl+Z) 실행 시 links가 편집 전 배열로 되돌아간다.
- [ ] links가 없는 기존 메모 행(5개 요소)·기존 이력 스냅샷을 불러와도 오류 없이 렌더링된다.

**범위 밖:**
- 개별 관계(엣지) 단위의 명시적 연결 — 관계 강조는 연결 테이블 쌍 사이 자동 파생만.
- 컬럼 단위 연결, 메모↔메모 연결.
- 편집 모드에서의 메모 클릭 동작 변경(기존대로 편집 모달 열기).
- 조회 모드 하이라이트 상태를 이미지 내보내기에 반영하는 것(내보내기는 기존 동작 유지, 배지는 SVG라 자연히 포함됨).

## 2. 개발디테일

**건드릴 파일:**

프론트엔드 (`frontend/src/`):
- `types.ts` — memos 행 주석을 `[id, text, x, y, color, links?]`로 갱신, `MAX_MEMO_LINKS = 10` 상수와 `rowStrArr(row, i): string[]` 헬퍼 추가.
- `state/schemaStore.tsx` — `State.selectedMemo: string | null` 추가, `{ type: "selectMemo"; id: string | null }` 액션 추가. `select`(테이블)와 `selectMemo`는 상호 배타(하나를 설정하면 다른 하나는 null). `applyOp`에서 선택된 메모가 사라지면 selectedMemo도 null. `editMode`·`focusDomain`·`search` 액션에서 selectedMemo도 null로 초기화.
- `state/opReducer.ts` — `applyMemoAdd`/`applyMemoApply`에 links 반영, `applyMemoApply`·`applyMemoMove`가 행 재구성 시 기존 links(인덱스 5)를 보존하도록 수정. `applyTableApply`에 메모 links의 옛 테이블명→새 테이블명 치환 추가, `applyDelete`에 links에서 삭제 테이블명 제거 추가.
- `state/undo.ts` — `invertOp`의 `memo.apply`·`memo.delete` 역연산 payload에 links 포함.
- `canvas/MemoNode.tsx` — props에 `linkCount: number`, `active: boolean`, `onSelect: (id: string) => void` 추가. 조회 모드 클릭 시 `onSelect(id)` 호출(편집 모드는 기존 `onOpen`). `linkCount > 0`이면 우상단에 배지 렌더: `<g className="memo-badge">` 안에 원(r=8) + 개수 텍스트, 위치는 (MEMO_W-4, -4) 부근. `active`면 rect에 `stroke-width` 강화용 `active` 클래스.
- `canvas/ErdCanvas.tsx` — `selectedMemo`를 store에서 읽어 강조 계산:
  - `memoLinks: Map<string, string[]>` (docMemos에서 파생, useMemo).
  - 활성 메모의 linked set = `new Set(memoLinks.get(selectedMemo))`.
  - `isNodeDim`: `selectedMemo` 활성 시 linked set 미포함 테이블은 dim.
  - 엣지: `selectedMemo` 활성 시 child·parent 모두 linked set에 있으면 `hl`, 아니면 dim.
  - 메모 dim: 기존 `memosDim` 불리언을 메모별 계산으로 교체 — `selectedMemo` 활성 시 자신 외 dim, 테이블 `selected` 시 links에 selected 포함 메모는 dim=false, search/focusDomain 시 전부 dim(기존 유지).
  - 배경 클릭 `clearFocus`에서 `selectMemo(null)`도 dispatch.
- `components/MemoModal.tsx` — 테이블 연결 섹션 추가: 필터 입력 + 체크박스 목록(`doc.tables` 이름순), 최대 10개 선택 시 나머지 비활성. 저장 op payload에 `links: string[]` 포함.
- `styles.css` — `.memo-node.active rect { stroke: var(--text); stroke-width: 2; }`, `.memo-badge circle/text` 스타일, 모달 링크 목록(`.memo-links`, max-height 160px, overflow-y auto) 스타일 추가.
- `exporters/image.ts` — buildStandaloneSvg의 내장 CSS(20~22행 부근)에 `.memo-badge` 스타일 추가(내보낸 SVG에서도 배지가 보이도록).

백엔드 (`backend/src/main/java/com/daou/erdstudio/`):
- `domain/ErdMemo.java` — `@Column(name = "links", nullable = false, length = 2000) private String links` 추가(JSON 배열 문자열, 기본 `"[]"`). 생성자·`update(text, color, links)`·getter 갱신. `ddl-auto: update`가 컬럼을 자동 추가하므로 별도 마이그레이션 없음.
- `service/OpService.java` — `memoLinks(JsonNode p, Long roomId)`: payload의 `links` 배열을 읽어 문자열 목록으로 변환, 개수 > 10이면 `IllegalArgumentException("메모에는 최대 10개의 테이블만 연결할 수 있습니다.")`, 방에 존재하지 않는 테이블명은 조용히 제거(동시 삭제 경합 대비). `applyMemoAdd`/`applyMemoApply`에서 사용. `applyTableApply`의 이름 변경 시 방 전체 메모 links에서 옛 이름→새 이름 치환, `applyTableDelete`에서 links에서 제거. `MAX_MEMO_LINKS = 10` 상수.
- `service/SchemaService.java` — `loadMemoRows`: links가 비어 있지 않으면 6번째 요소(List<String>)로 추가, 비면 5개 요소 유지. `insertMemos`: 행 인덱스 5의 배열을 links로 저장(존재 테이블명만 필터).
- `service/Rows.java` — `strList(List<Object> row, int index): List<String>` 헬퍼 추가(해당 인덱스가 List가 아니면 빈 목록).
- `web/dto/SchemaDoc.java` — memos 행 주석을 `[id, text, x, y, color, links?]`로 갱신.

문서:
- `erd-studio-ddl.sql` — `erd_memo`에 `links character varying(2000) DEFAULT '[]' NOT NULL` 컬럼 반영.

**인터페이스/시그니처:**
```typescript
// types.ts
export const MAX_MEMO_LINKS = 10;
export const rowStrArr = (row: Row, i: number): string[] => ...; // 인덱스 i가 배열이면 문자열 배열로, 아니면 []

// schemaStore.tsx
interface State { ...; selectedMemo: string | null }
type Action = ... | { type: "selectMemo"; id: string | null }

// MemoNode.tsx
interface Props { ...; linkCount: number; active: boolean; onSelect: (id: string) => void }
```
```java
// ErdMemo.java
public ErdMemo(Long roomId, String memoKey, String text, String color, double posX, double posY, int sortOrder, String links)
public void update(String text, String color, String links)
public String getLinks()

// Rows.java
public static List<String> strList(List<Object> row, int index)
```

**op payload 확장 (프로토콜):**
- `memo.add`: `{ id, text, x, y, color, links?: string[] }`
- `memo.apply`: `{ id, text, color, links?: string[] }`
- links 미포함 payload는 빈 배열로 처리(하위호환).

**구현 접근 (순서):**
1. 프론트 `types.ts` 헬퍼/상수 → `opReducer.ts` links 보존·치환 → `undo.ts` 역연산.
2. `schemaStore.tsx` selectedMemo 상태 → `ErdCanvas.tsx` 강조 계산 → `MemoNode.tsx` 배지·active·onSelect → `styles.css`·`exporters/image.ts`.
3. `MemoModal.tsx` 테이블 다중 선택 UI.
4. 백엔드 `ErdMemo` 엔티티 → `Rows.strList` → `OpService`(검증·치환·삭제 정리) → `SchemaService`(load/insert) → DTO 주석 → `erd-studio-ddl.sql`.
5. 백엔드 테스트 추가(OpServiceTest) → 전체 빌드·테스트.

**기존 코드 활용:**
- `ErdCanvas.tsx`의 `adjacency`/`isNodeDim`/`searchMatches` useMemo 패턴 — 메모 링크 파생 계산도 동일 패턴으로.
- `MemoNode`의 `registerEl`·`wasJustDragged()` — 드래그 직후 클릭 무시 로직을 onSelect에도 그대로 적용.
- `OpService.memoText/memoColor` 검증 헬퍼 패턴 — `memoLinks`도 같은 형태로.
- `ObjectMapper`(OpService에 이미 주입됨) — links JSON 직렬화/역직렬화에 사용.
- `OpServiceTest.addMemo`/`op()` 헬퍼 — links 테스트 케이스 작성에 재사용.

## 3. 제약사항

**하지 말 것:**
- 메모 행의 기존 인덱스(0~4: id, text, x, y, color) 의미를 바꾸지 않는다 — links는 인덱스 5 추가만.
- 새 op 타입을 만들지 않는다 — 기존 `memo.add`/`memo.apply` payload 확장으로만 해결한다.
- `erd_relation`·`erd_table` 등 다른 테이블의 스키마를 변경하지 않는다 — DB 변경은 `erd_memo.links` 컬럼 추가 하나뿐.
- 편집 모드의 메모 클릭 동작(모달 열기)과 메모 드래그 동작을 바꾸지 않는다.
- 관계(엣지)에 개별 식별자를 도입하지 않는다.
- `frontend/dist/` 빌드 산출물을 직접 수정하지 않는다.

**지킬 규칙:**
- 백엔드 검증 실패 메시지는 기존처럼 한국어 `IllegalArgumentException`으로 던진다.
- 프론트 행 접근은 `rowStr`/`rowNum`/`rowStrArr` 헬퍼로만, 백엔드는 `Rows.str`/`Rows.dbl`/`Rows.strList`로만 한다(직접 인덱싱 금지).
- 상태 변경은 모두 op 전송 → 서버 에코 → `applyOpToDoc` 경로를 따른다(로컬 선반영 금지 — 기존 구조와 동일).
- 주석·UI 문구는 기존 코드와 같이 한국어로 쓴다.
- `ErdNode`/`MemoNode`의 props를 원시값 중심으로 유지해 memo 리렌더 최적화를 깨지 않는다(배열 대신 `linkCount: number`·`active: boolean` 전달).

**경계값:**
- 메모당 연결 테이블 최대 10개 (`MAX_MEMO_LINKS`) — 초과 시 서버가 op 거부, 모달은 11번째 체크박스 비활성.
- `erd_memo.links` 컬럼 길이 2000자 — 직렬화 JSON이 2000자를 넘으면 서버가 op 거부(메시지: "연결 정보가 너무 깁니다.").
- 링크 대상 테이블명은 방에 존재하는 것만 저장(존재하지 않는 이름은 서버·`insertMemos`에서 조용히 제거).
- 기존 한도 유지: 메모 최대 200개/방, 본문 500자, memo_key 64자.

**의존성:** 신규 라이브러리 추가 금지 — 프론트·백엔드 모두 기존 의존성만 사용한다.

## 4. 테스트방법

**검증 명령:**
```bash
# 백엔드 — 기존 + 신규 테스트 전부 통과해야 함
cd backend && ./gradlew test

# 프론트엔드 — 타입 검사 + 빌드 무오류
cd frontend && npm run build
```

**통과 기준:** `./gradlew test`가 `BUILD SUCCESSFUL` 출력 + 실패 테스트 0건, `npm run build`가 exit code 0으로 종료.

**추가할 백엔드 테스트 (OpServiceTest, 기존 `op()`/`addMemo()` 헬퍼 재사용):**
- [ ] `memoApply_links를_저장한다` — 테이블 2개 생성 → links 포함 memo.apply → `getLinks()`가 두 이름을 담은 JSON.
- [ ] `memoApply_존재하지_않는_테이블은_links에서_제거한다` — 실존 1개 + 가짜 1개 전송 → 실존 1개만 저장.
- [ ] `memoApply_links가_10개를_넘으면_거부한다` — 11개 전송 → IllegalArgumentException.
- [ ] `tableApply_이름을_바꾸면_메모_links도_갱신된다` — 링크된 테이블 rename → links에 새 이름.
- [ ] `tableDelete_메모_links에서_제거된다` — 링크된 테이블 삭제 → links에서 사라짐.
- [ ] `loadDoc_links가_있으면_메모_행이_6요소다` — loadDoc 결과 memos 행 인덱스 5가 링크 배열, links 없는 메모는 5요소.

**엣지케이스:**
- [ ] links 없는 기존 메모 행(5요소) 로드 → `rowStrArr(m, 5)`가 `[]` 반환, 렌더 오류 없음.
- [ ] 링크된 테이블 2개 중 1개만 남기고 삭제 → 메모 클릭 시 남은 1개만 강조, 배지 "1".
- [ ] links가 1개(관계 없음)인 메모 클릭 → 테이블 1개만 강조되고 `hl` 엣지 0개.
- [ ] 링크가 0개인 메모를 조회 모드에서 클릭 → 하이라이트 변화 없음(배지도 없음).
- [ ] 메모 편집 모달에서 links 변경 후 Ctrl+Z → 이전 links 복원(memo.apply 역연산).
- [ ] schema.replace(JSON 불러오기·이력 복원)에 6요소 메모 행 포함 → links 저장·표시 유지.

**수동 확인 (브라우저 2탭):**
1. `docker compose up` 또는 백엔드·프론트 dev 서버 기동 후 같은 방에 2개 탭 접속.
2. 탭 A 편집 모드에서 메모 생성 → 모달에서 테이블 2개 체크 → 저장.
3. 탭 B에서 배지 "2" 표시 확인.
4. 탭 B 조회 모드에서 메모 클릭 → 두 테이블만 선명, 그 사이 관계선 강조(hl) 확인. 재클릭·배경 클릭으로 해제 확인.
5. 탭 A에서 링크된 테이블 클릭 → 그 메모가 흐려지지 않음 확인.
6. 링크된 테이블 이름 변경 후 새로고침 → 배지 유지·클릭 강조 정상 확인.
