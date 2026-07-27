import { useEffect } from "react";
import { useDispatch, useStore } from "../state/schemaStore";

const HIDE_AFTER_MS = 3000;

/** 하단 알림 — 3초 뒤 자동으로 사라진다. */
export function Toast() {
  const { toast } = useStore();
  const dispatch = useDispatch();

  useEffect(() => {
    if (!toast) {
      return;
    }
    const timer = window.setTimeout(() => dispatch({ type: "toast", toast: null }), HIDE_AFTER_MS);
    return () => window.clearTimeout(timer);
  }, [toast, dispatch]);

  if (!toast) {
    return null;
  }
  return <div className={`toast ${toast.kind}`}>{toast.message}</div>;
}
