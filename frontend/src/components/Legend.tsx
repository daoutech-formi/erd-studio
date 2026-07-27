import { useMemo } from "react";
import { useDispatch, useStore } from "../state/schemaStore";
import { rowStr } from "../types";

/** 도메인 범례 — 클릭하면 해당 도메인만 강조한다. */
export function Legend() {
  const { doc, focusDomain } = useStore();
  const dispatch = useDispatch();

  const counts = useMemo(() => {
    const map: Record<string, number> = {};
    for (const t of doc?.tables ?? []) {
      const d = rowStr(t, 1);
      map[d] = (map[d] ?? 0) + 1;
    }
    return map;
  }, [doc]);

  if (!doc) {
    return null;
  }
  return (
    <div className="legend">
      <div className="legend-title">도메인</div>
      {Object.entries(doc.domains).map(([key, d]) => (
        <div
          key={key}
          className={`row${focusDomain === key ? " on" : ""}`}
          onClick={() => dispatch({ type: "focusDomain", domain: focusDomain === key ? null : key })}
        >
          <span className="sw" style={{ background: d.color }} />
          {d.name} <span className="cnt">({counts[key] ?? 0})</span>
        </div>
      ))}
    </div>
  );
}
