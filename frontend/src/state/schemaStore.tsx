import { createContext, useContext, useReducer, type Dispatch, type ReactNode } from "react";
import type { ClientInfo, Op, SchemaDoc } from "../types";
import { applyOpToDoc } from "./opReducer";

export interface Toast {
  message: string;
  kind: "ok" | "err";
}

export interface State {
  doc: SchemaDoc | null;
  error: string | null;
  selected: string | null;
  search: string;
  focusDomain: string | null;
  editMode: boolean;
  /** 편집 폼이 열려 있는(내가 락을 잡은) 테이블명. */
  editing: string | null;
  presence: ClientInfo[];
  locks: Record<string, ClientInfo>;
  connected: boolean;
  historyOpen: boolean;
  toast: Toast | null;
}

export type Action =
  | { type: "loaded"; doc: SchemaDoc }
  | { type: "loadError"; message: string }
  | { type: "applyOp"; op: Op }
  | { type: "select"; name: string | null }
  | { type: "search"; text: string }
  | { type: "focusDomain"; domain: string | null }
  | { type: "editMode"; on: boolean }
  | { type: "editing"; name: string | null }
  | { type: "presence"; users: ClientInfo[] }
  | { type: "locks"; locks: Record<string, ClientInfo> }
  | { type: "connected"; on: boolean }
  | { type: "historyOpen"; on: boolean }
  | { type: "toast"; toast: Toast | null };

const initialState: State = {
  doc: null,
  error: null,
  selected: null,
  search: "",
  focusDomain: null,
  editMode: false,
  editing: null,
  presence: [],
  locks: {},
  connected: false,
  historyOpen: false,
  toast: null,
};

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case "loaded":
      return { ...state, doc: action.doc, error: null, selected: null, editing: null };
    case "loadError":
      return { ...state, error: action.message };
    case "applyOp": {
      if (!state.doc) {
        return state;
      }
      const doc = applyOpToDoc(state.doc, action.op);
      // 내가 보던/편집하던 테이블이 사라졌으면 선택을 해제한다.
      const gone = (name: string | null) =>
        name !== null && !doc.tables.some((t) => String(t[0]) === name);
      return {
        ...state,
        doc,
        selected: gone(state.selected) ? null : state.selected,
        editing: gone(state.editing) ? null : state.editing,
      };
    }
    case "select":
      return { ...state, selected: action.name, focusDomain: null };
    case "search":
      return { ...state, search: action.text, focusDomain: null };
    case "focusDomain":
      return { ...state, focusDomain: action.domain, selected: null, search: "" };
    case "editMode":
      return { ...state, editMode: action.on, selected: null, editing: null };
    case "editing":
      return { ...state, editing: action.name };
    case "presence":
      return { ...state, presence: action.users };
    case "locks":
      return { ...state, locks: action.locks };
    case "connected":
      return { ...state, connected: action.on };
    case "historyOpen":
      return { ...state, historyOpen: action.on };
    case "toast":
      return { ...state, toast: action.toast };
  }
}

const StateContext = createContext<State>(initialState);
const DispatchContext = createContext<Dispatch<Action>>(() => undefined);

export function StoreProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initialState);
  return (
    <StateContext.Provider value={state}>
      <DispatchContext.Provider value={dispatch}>{children}</DispatchContext.Provider>
    </StateContext.Provider>
  );
}

export const useStore = (): State => useContext(StateContext);
export const useDispatch = (): Dispatch<Action> => useContext(DispatchContext);
