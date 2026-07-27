import { downloadBlob, downloadText } from "./download";

// 현재 다이어그램 전체를 PNG/SVG 파일로 만든다. 외부 리소스 없이 CSS를 내장한다.

const PADDING = 24;
const PNG_SCALE = 2;
const BACKGROUND = "#10141f";

const SVG_CSS = `
  text { fill: #dfe4f0; font-size: 11px; font-family: "Apple SD Gothic Neo", "Malgun Gothic", sans-serif; }
  .node text.key { fill: #7b8399; font-size: 9px; }
  .node .lock-badge { display: none; }
  .domain-label { font-size: 14px; font-weight: 700; }
  .edge { fill: none; stroke: #33405e; stroke-width: 1; opacity: 0.55; }
  .edge.hl { stroke: #6ea8ff; stroke-width: 2; opacity: 1; }
`;

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

  const style = document.createElementNS("http://www.w3.org/2000/svg", "style");
  style.textContent = SVG_CSS;
  const bg = document.createElementNS("http://www.w3.org/2000/svg", "rect");
  bg.setAttribute("x", String(box.x - PADDING));
  bg.setAttribute("y", String(box.y - PADDING));
  bg.setAttribute("width", String(width));
  bg.setAttribute("height", String(height));
  bg.setAttribute("fill", BACKGROUND);

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
