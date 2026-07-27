import { useDispatch, useStore } from "../state/schemaStore";

/** 테이블명·컬럼명·컬럼 설명을 부분 일치로 검색한다. */
export function SearchBox() {
  const { search } = useStore();
  const dispatch = useDispatch();
  return (
    <input
      id="search"
      type="text"
      value={search}
      placeholder="테이블/컬럼 검색 (예: bill_no)"
      onChange={(e) => dispatch({ type: "search", text: e.target.value })}
    />
  );
}
