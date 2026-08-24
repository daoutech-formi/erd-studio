/** opKind 코드 → 한글 표기. 목록에 없는 값(구버전 등)은 코드 그대로 보여준다. */
const OP_LABELS: Record<string, string> = {
  "table.add": "테이블 추가",
  "table.apply": "테이블 수정",
  "table.delete": "테이블 삭제",
  "table.move": "테이블 이동",
  "domain.apply": "도메인 변경",
  "memo.add": "메모 추가",
  "memo.apply": "메모 수정",
  "memo.delete": "메모 삭제",
  "memo.move": "메모 이동",
  "memo.resize": "메모 크기 조절",
  "schema.replace": "전체 교체",
  "history.restore": "이력 복원",
};

export const opLabel = (kind: string): string => OP_LABELS[kind] ?? kind;
