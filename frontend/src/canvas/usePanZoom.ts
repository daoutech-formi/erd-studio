import { useEffect, useMemo, useRef, type RefObject } from "react";

export interface ViewState {
  tx: number;
  ty: number;
  scale: number;
}

export interface PanZoomApi {
  reset: () => void;
  zoomBy: (factor: number) => void;
  getView: () => ViewState;
  /** 월드 좌표 (wx,wy)가 화면 중앙에 오도록 이동한다 (미니맵 클릭 이동용). */
  centerOn: (wx: number, wy: number) => void;
  /** 팬/줌 변경 구독 — 해제 함수를 돌려준다 (미니맵 뷰포트 표시용). */
  subscribe: (fn: (v: ViewState) => void) => () => void;
}

const INITIAL = { tx: 60, ty: 20, scale: 0.65 };

/**
 * 팬/줌 — React 상태를 거치지 않고 viewport <g>의 transform을 rAF로 직접 갱신한다.
 * 노드/메모 위에서 시작한 mousedown은 onDown에서 걸러 팬하지 않는다.
 */
export function usePanZoom(
  stageRef: RefObject<HTMLDivElement>,
  viewportRef: RefObject<SVGGElement>,
  onBackgroundDown: () => void,
): PanZoomApi {
  const view = useRef({ ...INITIAL });
  const raf = useRef(0);
  const listeners = useRef(new Set<(v: ViewState) => void>());
  const callbacks = useRef(onBackgroundDown);
  callbacks.current = onBackgroundDown;

  const apply = () => {
    cancelAnimationFrame(raf.current);
    raf.current = requestAnimationFrame(() => {
      const { tx, ty, scale } = view.current;
      viewportRef.current?.setAttribute("transform", `translate(${tx},${ty}) scale(${scale})`);
      listeners.current.forEach((fn) => fn({ tx, ty, scale }));
    });
  };

  useEffect(() => {
    const stage = stageRef.current;
    if (!stage) {
      return;
    }
    apply();
    let dragging = false;
    let sx = 0;
    let sy = 0;

    const onDown = (e: MouseEvent) => {
      // 노드/메모 드래그와 팬이 겹치지 않게 한다 — React 합성 이벤트의 stopPropagation은
      // stage에 직접 붙인 이 네이티브 리스너보다 늦게 실행되어 여기서 직접 걸러야 한다.
      if ((e.target as Element | null)?.closest?.(".node, .memo-node")) {
        return;
      }
      dragging = true;
      sx = e.clientX - view.current.tx;
      sy = e.clientY - view.current.ty;
      stage.classList.add("grabbing");
      callbacks.current();
    };
    const onMove = (e: MouseEvent) => {
      if (!dragging) {
        return;
      }
      view.current.tx = e.clientX - sx;
      view.current.ty = e.clientY - sy;
      apply();
    };
    const onUp = () => {
      dragging = false;
      stage.classList.remove("grabbing");
    };
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const factor = e.deltaY < 0 ? 1.12 : 0.89;
      const rect = stage.getBoundingClientRect();
      const mx = e.clientX - rect.left;
      const my = e.clientY - rect.top;
      view.current.tx = mx - (mx - view.current.tx) * factor;
      view.current.ty = my - (my - view.current.ty) * factor;
      view.current.scale *= factor;
      apply();
    };

    stage.addEventListener("mousedown", onDown);
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    stage.addEventListener("wheel", onWheel, { passive: false });
    return () => {
      stage.removeEventListener("mousedown", onDown);
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      stage.removeEventListener("wheel", onWheel);
      cancelAnimationFrame(raf.current);
    };
    // stageRef/viewportRef는 마운트 후 고정이다.
    // eslint 없이 진행하므로 의존성은 의도적으로 비워 둔다.
  }, []);

  // ref만 참조하므로 항상 같은 객체를 돌려줘 구독자(미니맵)의 재구독을 막는다.
  return useMemo(
    () => ({
      reset: () => {
        view.current = { ...INITIAL };
        apply();
      },
      zoomBy: (factor: number) => {
        view.current.scale *= factor;
        apply();
      },
      getView: () => ({ ...view.current }),
      centerOn: (wx: number, wy: number) => {
        const stage = stageRef.current;
        if (!stage) {
          return;
        }
        view.current.tx = stage.clientWidth / 2 - wx * view.current.scale;
        view.current.ty = stage.clientHeight / 2 - wy * view.current.scale;
        apply();
      },
      subscribe: (fn: (v: ViewState) => void) => {
        listeners.current.add(fn);
        return () => listeners.current.delete(fn);
      },
    }),
    // eslint-disable 없이 — 전부 ref 기반이라 안전하다.
    [],
  );
}
