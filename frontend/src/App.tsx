import { useCallback, useEffect, useRef } from "react";
import { fetchSchema } from "./api/http";
import { ErdCanvas } from "./canvas/ErdCanvas";
import type { PanZoomApi } from "./canvas/usePanZoom";
import { DetailPanel } from "./components/DetailPanel";
import { Header } from "./components/Header";
import { Legend } from "./components/Legend";
import { Toast } from "./components/Toast";
import { useDispatch } from "./state/schemaStore";

export function App() {
  const dispatch = useDispatch();
  const panZoomRef = useRef<PanZoomApi | null>(null);

  useEffect(() => {
    fetchSchema()
      .then((doc) => dispatch({ type: "loaded", doc }))
      .catch((e: Error) => dispatch({ type: "loadError", message: e.message }));
  }, [dispatch]);

  const onNodeClick = useCallback(
    (name: string) => dispatch({ type: "select", name }),
    [dispatch],
  );

  return (
    <div id="app">
      <Header
        onReset={() => {
          panZoomRef.current?.reset();
          dispatch({ type: "select", name: null });
          dispatch({ type: "search", text: "" });
        }}
        onZoom={(f) => panZoomRef.current?.zoomBy(f)}
      />
      <div id="body">
        <ErdCanvas apiRef={panZoomRef} onNodeClick={onNodeClick} />
        <Legend />
        <DetailPanel />
        <Toast />
      </div>
    </div>
  );
}
