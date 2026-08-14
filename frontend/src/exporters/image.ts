import { downloadBlob, downloadText } from "./download";

// 현재 다이어그램 전체를 PNG/SVG/PDF(인쇄)로 만든다. 외부 리소스 없이 현재 테마 CSS를 내장한다.

const PADDING = 24;
const PNG_SCALE = 2;

/** 현재 테마의 CSS 토큰을 읽어 내보내기용 스타일을 만든다. */
function buildSvgCss(): { css: string; background: string } {
  const style = getComputedStyle(document.documentElement);
  const v = (name: string, fallback: string): string => style.getPropertyValue(name).trim() || fallback;
  const css = `
  text { fill: ${v("--text", "#dfe4f0")}; font-size: 11px; font-family: "Apple SD Gothic Neo", "Malgun Gothic", sans-serif; }
  .node rect { fill: ${v("--node-fill", "#1b2336")}; }
  .node text.key { fill: ${v("--muted", "#7b8399")}; font-size: 9px; }
  .node .lock-badge { display: none; }
  .domain-label { font-size: 14px; font-weight: 700; }
  .edge { fill: none; stroke: ${v("--edge", "#33405e")}; stroke-width: 1; opacity: 0.55; }
  .edge.hl { stroke: ${v("--edge-hl", "#6ea8ff")}; stroke-width: 2; opacity: 1; }
  .memo-node rect { stroke: rgba(0, 0, 0, 0.28); }
  .memo-node text { fill: #453d20; font-size: 12px; }
  .memo-fold { fill: rgba(0, 0, 0, 0.16); }
`;
  return { css, background: v("--bg", "#10141f") };
}

function buildStandaloneSvg(): { svg: string; width: number; height: number } | null {
  const viewport = document.getElementById("viewport") as SVGGElement | null;
  if (!viewport) {
    return null;
  }
  const box = viewport.getBBox();
  const width = Math.ceil(box.width + PADDING * 2);
  const height = Math.ceil(box.height + PADDING * 2);
  const clone = viewport.cloneNode(true) as SVGGElement;
  clone.removeAttribute("transform");

  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("xmlns", "http://www.w3.org/2000/svg");
  svg.setAttribute("width", String(width));
  svg.setAttribute("height", String(height));
  svg.setAttribute("viewBox", `${box.x - PADDING} ${box.y - PADDING} ${width} ${height}`);

  const theme = buildSvgCss();
  const style = document.createElementNS("http://www.w3.org/2000/svg", "style");
  style.textContent = theme.css;
  const bg = document.createElementNS("http://www.w3.org/2000/svg", "rect");
  bg.setAttribute("x", String(box.x - PADDING));
  bg.setAttribute("y", String(box.y - PADDING));
  bg.setAttribute("width", String(width));
  bg.setAttribute("height", String(height));
  bg.setAttribute("fill", theme.background);

  svg.appendChild(style);
  svg.appendChild(bg);
  svg.appendChild(clone);
  return { svg: new XMLSerializer().serializeToString(svg), width, height };
}

export function exportSvg(onError: (message: string) => void): void {
  const built = buildStandaloneSvg();
  if (!built) {
    onError("다이어그램을 찾을 수 없습니다.");
    return;
  }
  downloadText("erd-studio.svg", built.svg, "image/svg+xml;charset=utf-8");
}

export function exportPng(onError: (message: string) => void): void {
  const built = buildStandaloneSvg();
  if (!built) {
    onError("다이어그램을 찾을 수 없습니다.");
    return;
  }
  const image = new Image();
  image.onload = () => {
    const canvas = document.createElement("canvas");
    canvas.width = built.width * PNG_SCALE;
    canvas.height = built.height * PNG_SCALE;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      onError("캔버스를 만들 수 없습니다.");
      return;
    }
    ctx.scale(PNG_SCALE, PNG_SCALE);
    ctx.drawImage(image, 0, 0);
    canvas.toBlob((blob) => {
      if (blob) {
        downloadBlob("erd-studio.png", blob);
      } else {
        onError("PNG 변환에 실패했습니다.");
      }
    }, "image/png");
  };
  image.onerror = () => onError("SVG 렌더링에 실패했습니다.");
  image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(built.svg)}`;
}

/** 인쇄 창을 열어 브라우저의 'PDF로 저장'으로 내보낸다 (외부 라이브러리 없음). */
export function exportPdf(onError: (message: string) => void): void {
  const built = buildStandaloneSvg();
  if (!built) {
    onError("다이어그램을 찾을 수 없습니다.");
    return;
  }
  const win = window.open("", "_blank");
  if (!win) {
    onError("팝업이 차단되어 인쇄 창을 열 수 없습니다.");
    return;
  }
  const orientation = built.width >= built.height ? "landscape" : "portrait";
  win.document.write(`<!doctype html>
<html>
<head>
  <meta charset="utf-8" />
  <title>ERD Studio</title>
  <style>
    @page { size: A4 ${orientation}; margin: 8mm; }
    html, body { margin: 0; }
    svg { width: 100%; height: auto; }
  </style>
</head>
<body>${built.svg}</body>
</html>`);
  win.document.close();
  win.focus();
  // 렌더링이 끝난 뒤 인쇄 대화상자를 연다 (사용자가 'PDF로 저장' 선택).
  window.setTimeout(() => win.print(), 300);
}
