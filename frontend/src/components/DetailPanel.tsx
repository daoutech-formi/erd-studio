import { useDispatch, useStore } from "../state/schemaStore";
import { rowStr } from "../types";

const MAX_CHILDREN = 20;

/** 보기 모드에서 선택한 테이블의 도메인·관계·컬럼 상세를 보여준다. */
export function DetailPanel() {
  const { doc, selected } = useStore();
  const dispatch = useDispatch();
  if (!doc || !selected) {
    return null;
  }
  const row = doc.tables.find((t) => rowStr(t, 0) === selected);
  if (!row) {
    return null;
  }
  const domain = doc.domains[rowStr(row, 1)];
  const parents = doc.relations
    .filter((r) => rowStr(r, 0) === selected)
    .map((r) => rowStr(r, 1) + (rowStr(r, 2) ? ` (${rowStr(r, 2)})` : ""));
  const children = doc.relations
    .filter((r) => rowStr(r, 1) === selected)
    .map((r) => rowStr(r, 0) + (rowStr(r, 2) ? ` (${rowStr(r, 2)})` : ""));
  const cols = doc.columns[selected];

  return (
    <div className="panel">
      <div className="panel-head">
        <h3>{selected}</h3>
        <button className="mini" onClick={() => dispatch({ type: "select", name: null })}>×</button>
      </div>
      {domain && (
        <span className="tag" style={{ background: `${domain.color}33`, color: domain.color }}>{domain.name}</span>
      )}
      <div className="desc">{rowStr(row, 2)}</div>
      {parents.length > 0 && (
        <div className="sect-block">
          <b>참조 →</b>
          <ul>{parents.map((p) => <li key={p}>{p}</li>)}</ul>
        </div>
      )}
      {children.length > 0 && (
        <div className="sect-block">
          <b>← 참조됨 ({children.length})</b>
          <ul>
            {children.slice(0, MAX_CHILDREN).map((c) => <li key={c}>{c}</li>)}
            {children.length > MAX_CHILDREN && <li>… 외 {children.length - MAX_CHILDREN}개</li>}
          </ul>
        </div>
      )}
      <div className="sect">컬럼 {cols ? `(${cols.length})` : ""}</div>
      {cols ? (
        <table className="cols">
          <tbody>
            {cols.map((c, i) => (
              <tr key={`${rowStr(c, 0)}#${i}`}>
                <td className="cn">
                  {rowStr(c, 0)}
                  {rowStr(c, 3) && rowStr(c, 3).split("/").map((f) => (
                    <span key={f} className={`badge ${f.toLowerCase()}`}>{f}</span>
                  ))}
                </td>
                <td className="ct">{rowStr(c, 1)}</td>
                <td className="cc">{rowStr(c, 2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="hint">컬럼 정보 없음</div>
      )}
      <div className="hint">PK 기본키 · UK 유니크 · FK 참조키 · 빈 곳 클릭 시 해제</div>
    </div>
  );
}
