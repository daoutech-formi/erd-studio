// ============================================================
//  애드콘 ERD 뷰어/편집기 — 스키마 데이터는 백엔드 API(PostgreSQL)에서 로드
// ============================================================
let DOMAINS = {};
let T = [];
let R = [];
let COLS = {};
let domainOrder = [];

const API = "/api/schema";

// ---------- 레이아웃 & 렌더 ----------
const NW = 150, NH = 34, GAP_Y = 46, COL_W = 330, PAD = 40;
const svg = document.getElementById("svg");
const gEdges = document.getElementById("edges");
const gNodes = document.getElementById("nodes");
const NS = "http://www.w3.org/2000/svg";
function el(tag, attrs) { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); return e; }

let byDomain = {}, nodeMap = {}, edgeEls = [], adj = {}, nodeEls = {};

function computeLayout() {
  domainOrder = Object.keys(DOMAINS);
  byDomain = {}; domainOrder.forEach(d => byDomain[d] = []);
  nodeMap = {};
  T.forEach(t => {
    const [id, domain, key, hub] = t;
    const n = { id, domain: byDomain[domain] ? domain : domainOrder[0], key, hub: !!hub };
    nodeMap[id] = n; byDomain[n.domain].push(n);
  });
  const gridCols = 5;
  const innerColsOf = list => list.length > 12 ? 2 : 1;
  const rowsOf = list => Math.ceil(list.length / innerColsOf(list));
  const numGridRows = Math.ceil(domainOrder.length / gridCols);
  const gridRowY = []; let acc = 0;
  for (let gr = 0; gr < numGridRows; gr++) {
    let maxRows = 0;
    domainOrder.forEach((d, i) => { if (Math.floor(i / gridCols) === gr) maxRows = Math.max(maxRows, rowsOf(byDomain[d])); });
    gridRowY[gr] = acc; acc += maxRows * GAP_Y + 90;
  }
  domainOrder.forEach((d, di) => {
    const gx = di % gridCols, gy = Math.floor(di / gridCols);
    const list = byDomain[d]; const colBaseX = gx * COL_W + PAD;
    const innerCols = innerColsOf(list); const baseY = gridRowY[gy];
    list.forEach((n, i) => {
      const ic = i % innerCols, ir = Math.floor(i / innerCols);
      n.x = colBaseX + ic * (NW + 12); n.y = baseY + 60 + ir * GAP_Y;
    });
    byDomain[d]._label = { x: colBaseX, y: baseY + 30, name: DOMAINS[d].name };
  });
}

function renderAll() {
  computeLayout();
  gNodes.innerHTML = ""; gEdges.innerHTML = "";
  domainOrder.forEach(d => {
    if (!byDomain[d].length) return;
    const lb = byDomain[d]._label;
    const t = el("text", { x: lb.x, y: lb.y, "font-size": 14, "font-weight": "700", fill: DOMAINS[d].color });
    t.textContent = "● " + lb.name; gNodes.appendChild(t);
  });
  edgeEls = []; adj = {};
  R.forEach(r => {
    const [c, p] = r; const cn = nodeMap[c], pn = nodeMap[p];
    if (!cn || !pn) return;
    const x1 = cn.x + NW/2, y1 = cn.y, x2 = pn.x + NW/2, y2 = pn.y;
    const mx = (x1 + x2) / 2;
    const path = el("path", { d: `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`, class: "edge" });
    path.dataset.c = c; path.dataset.p = p;
    gEdges.appendChild(path); edgeEls.push(path);
    (adj[c] = adj[c] || []).push(p); (adj[p] = adj[p] || []).push(c);
  });
  nodeEls = {};
  Object.values(nodeMap).forEach(n => {
    const g = el("g", { class: "node" + (n.hub ? " hub" : ""), transform: `translate(${n.x},${n.y - NH/2})` });
    const rect = el("rect", { width: NW, height: NH, fill: "#1b2336", stroke: DOMAINS[n.domain].color });
    const t1 = el("text", { x: 8, y: 14 }); t1.textContent = n.id.length > 22 ? n.id.slice(0,21)+"…" : n.id;
    const t2 = el("text", { x: 8, y: 27, class: "key" }); t2.textContent = n.key;
    g.appendChild(rect); g.appendChild(t1); g.appendChild(t2);
    g.addEventListener("click", (e) => { e.stopPropagation(); selectNode(n.id); });
    g.addEventListener("mouseenter", () => hoverNode(n.id, true));
    g.addEventListener("mouseleave", () => { if (!selected) clearHL(); });
    gNodes.appendChild(g); nodeEls[n.id] = g;
  });
  buildLegend();
  document.getElementById("subtitle").textContent =
    `DB: 3n1_db (donut) · 총 ${T.length}개 테이블 · 관계는 컬럼명 규칙 기반 추론(명시적 FK 없음)`;
}

// ---------- 인터랙션: 강조 ----------
let selected = null;
function setHL(centerId) {
  const connected = new Set([centerId, ...(adj[centerId] || [])]);
  edgeEls.forEach(e => {
    const on = e.dataset.c === centerId || e.dataset.p === centerId;
    e.classList.toggle("hl", on);
    e.classList.toggle("dim", !on);
  });
  Object.values(nodeMap).forEach(n => {
    nodeEls[n.id].classList.toggle("dim", !connected.has(n.id));
  });
}
function clearHL() {
  edgeEls.forEach(e => { e.classList.remove("hl","dim"); });
  Object.values(nodeMap).forEach(n => nodeEls[n.id].classList.remove("dim"));
  panel.style.display = "none";
}
function hoverNode(id) { if (!selected) setHL(id); }
function escapeHtml(s){ return String(s==null?"":s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c])); }
function selectNode(id) {
  selected = id; setHL(id);
  if (editMode) { renderEditForm(id); return; }
  const n = nodeMap[id];
  const parents = R.filter(r => r[0] === id).map(r => r[1] + (r[2]?` (${r[2]})`:""));
  const children = R.filter(r => r[1] === id).map(r => r[0] + (r[2]?` (${r[2]})`:""));
  const cols = COLS[id];
  const badge = f => f ? f.split("/").map(x=>`<span class="badge ${x.toLowerCase()}">${x}</span>`).join("") : "";
  const colsHtml = cols
    ? `<div class="sect">컬럼 (${cols.length})</div>
       <table class="cols">${cols.map(c=>`<tr><td class="cn">${escapeHtml(c[0])}${badge(c[3])}</td><td class="ct">${escapeHtml(c[1])}</td><td class="cc">${escapeHtml(c[2]||"")}</td></tr>`).join("")}</table>`
    : `<div class="sect">컬럼</div><div class="hint">컬럼 정보 없음</div>`;
  panel.style.display = "block";
  panel.innerHTML = `<h3>${escapeHtml(id)}</h3>
    <span class="tag" style="background:${DOMAINS[n.domain].color}33;color:${DOMAINS[n.domain].color}">${DOMAINS[n.domain].name}</span>
    <div>${escapeHtml(n.key)}</div>
    ${parents.length?`<div style="margin-top:8px"><b>참조 →</b><ul>${parents.map(x=>`<li>${escapeHtml(x)}</li>`).join("")}</ul></div>`:""}
    ${children.length?`<div style="margin-top:8px"><b>← 참조됨 (${children.length})</b><ul>${children.slice(0,20).map(x=>`<li>${escapeHtml(x)}</li>`).join("")}${children.length>20?`<li>… 외 ${children.length-20}개</li>`:""}</ul></div>`:""}
    ${colsHtml}
    <div class="hint">PK 기본키 · UK 유니크 · FK 참조키 · 빈 곳 클릭 시 해제</div>`;
  panel.scrollTop = 0;
}
const panel = document.getElementById("panel");

// ---------- 팬/줌 ----------
let scale = 0.65, tx = 60, ty = 20, dragging = false, sx, sy;
const vp = document.getElementById("viewport");
const stage = document.getElementById("stage");
function apply() { vp.setAttribute("transform", `translate(${tx},${ty}) scale(${scale})`); }
apply();
stage.addEventListener("mousedown", e => { dragging = true; sx = e.clientX - tx; sy = e.clientY - ty; stage.classList.add("grabbing"); if (e.target === svg || e.target.id==="viewport" || e.target===stage) { selected = null; clearHL(); } });
window.addEventListener("mousemove", e => { if (!dragging) return; tx = e.clientX - sx; ty = e.clientY - sy; apply(); });
window.addEventListener("mouseup", () => { dragging = false; stage.classList.remove("grabbing"); });
stage.addEventListener("wheel", e => { e.preventDefault(); const f = e.deltaY < 0 ? 1.12 : 0.89; const rect = stage.getBoundingClientRect(); const mx = e.clientX - rect.left, my = e.clientY - rect.top; tx = mx - (mx - tx) * f; ty = my - (my - ty) * f; scale *= f; apply(); }, { passive: false });
document.getElementById("zin").onclick = () => { scale *= 1.2; apply(); };
document.getElementById("zout").onclick = () => { scale *= 0.83; apply(); };
document.getElementById("reset").onclick = () => { scale = 0.65; tx = 60; ty = 20; selected = null; clearHL(); document.getElementById("search").value=""; apply(); };

// ---------- 검색 ----------
document.getElementById("search").addEventListener("input", e => {
  const q = e.target.value.trim().toLowerCase();
  if (!q) { clearHL(); selected=null; return; }
  const matches = new Set(Object.keys(nodeMap).filter(id => id.toLowerCase().includes(q)));
  edgeEls.forEach(el2 => el2.classList.add("dim"));
  Object.values(nodeMap).forEach(n => nodeEls[n.id].classList.toggle("dim", !matches.has(n.id)));
});

// ---------- 레전드 ----------
const legend = document.getElementById("legend");
function buildLegend() {
  legend.innerHTML = "<div style='font-weight:700;margin-bottom:6px'>도메인</div>" +
    domainOrder.map(d => `<div class="row" data-d="${d}"><span class="sw" style="background:${DOMAINS[d].color}"></span>${DOMAINS[d].name} <span style="color:#66708a">(${byDomain[d].length})</span></div>`).join("");
  legend.querySelectorAll(".row").forEach(row => {
    row.addEventListener("click", () => {
      const d = row.dataset.d;
      const ids = new Set(byDomain[d].map(n => n.id));
      edgeEls.forEach(el2 => el2.classList.add("dim"));
      Object.values(nodeMap).forEach(n => nodeEls[n.id].classList.toggle("dim", !ids.has(n.id)));
      selected = "domain";
    });
  });
}

// ============================================================
//  스키마 편집 기능
// ============================================================
let editMode = false;
const FLAGS = ["", "PK", "UK", "FK", "UK/FK"];

function findTable(id){ return T.find(t => t[0] === id); }

function renderEditForm(id) {
  const n = nodeMap[id];
  const cols = COLS[id] || (COLS[id] = []);
  const domOpts = domainOrder.map(d => `<option value="${d}" ${d===n.domain?"selected":""}>${DOMAINS[d].name}</option>`).join("");
  const otherTables = T.map(t=>t[0]).filter(t=>t!==id).sort();
  const parents = R.map((r,i)=>({r,i})).filter(x=>x.r[0]===id);
  panel.style.display = "block";
  panel.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center">
      <h3 style="margin:0">테이블 편집</h3>
      <button class="mini danger" id="ed-del">테이블 삭제</button>
    </div>
    <label class="fl">테이블명</label>
    <input class="fi" id="ed-name" value="${escapeHtml(id)}">
    <label class="fl">도메인</label>
    <select class="fi" id="ed-domain">${domOpts}</select>
    <label class="fl">설명</label>
    <input class="fi" id="ed-key" value="${escapeHtml(n.key)}">
    <div class="sect">컬럼 <button class="mini" id="ed-addcol">+ 추가</button></div>
    <div id="ed-cols">
      ${cols.map((c,i)=>colRow(c,i)).join("")}
    </div>
    <div class="sect">참조 관계 (이 테이블 → 대상) <button class="mini" id="ed-addrel">+ 추가</button></div>
    <div id="ed-rels">
      ${parents.map(x=>relRow(x.r,x.i,otherTables)).join("") || '<div class="hint">없음</div>'}
    </div>
    <div style="margin-top:12px;display:flex;gap:6px">
      <button class="mini primary" id="ed-apply">적용</button>
      <button class="mini" id="ed-cancel">취소</button>
    </div>
    <div class="hint">적용을 눌러야 다이어그램에 반영됩니다. 서버 반영은 상단 '서버에 저장'.</div>`;
  panel.scrollTop = 0;

  document.getElementById("ed-addcol").onclick = () => { cols.push(["new_col","varchar(50)","",""]); renderEditForm(id); };
  document.getElementById("ed-addrel").onclick = () => { R.push([id, otherTables[0]||id, ""]); renderEditForm(id); };
  document.getElementById("ed-del").onclick = () => { if(confirm(`'${id}' 테이블을 삭제할까요? (관련 관계도 함께 삭제)`)) deleteTable(id); };
  document.getElementById("ed-cancel").onclick = () => { selected=null; clearHL(); };
  document.getElementById("ed-apply").onclick = () => applyEdit(id);
  panel.querySelectorAll(".cdel").forEach(b => b.onclick = () => b.closest(".crow").remove());
  panel.querySelectorAll(".rdel").forEach(b => b.onclick = () => b.closest(".rrow").remove());
}
function colRow(c,i){
  return `<div class="crow" data-i="${i}">
    <input class="ci cn2" value="${escapeHtml(c[0])}" placeholder="컬럼명">
    <input class="ci ct2" value="${escapeHtml(c[1])}" placeholder="타입">
    <input class="ci cc2" value="${escapeHtml(c[2]||"")}" placeholder="설명">
    <select class="ci cf2">${FLAGS.map(f=>`<option ${f===(c[3]||"")?"selected":""}>${f}</option>`).join("")}</select>
    <button class="mini danger cdel">×</button>
  </div>`;
}
function relRow(r,idx,others){
  return `<div class="rrow" data-idx="${idx}">
    <span style="color:#7b8399">→</span>
    <select class="ci rt2">${others.map(t=>`<option ${t===r[1]?"selected":""}>${t}</option>`).join("")}</select>
    <input class="ci rl2" value="${escapeHtml(r[2]||"")}" placeholder="라벨(선택)" style="max-width:80px">
    <button class="mini danger rdel">×</button>
  </div>`;
}
function applyEdit(oldId){
  const newName = document.getElementById("ed-name").value.trim();
  const domain = document.getElementById("ed-domain").value;
  const key = document.getElementById("ed-key").value.trim();
  if(!newName){ alert("테이블명을 입력하세요."); return; }
  if(newName!==oldId && T.some(t=>t[0]===newName)){ alert("이미 존재하는 테이블명입니다."); return; }
  // 컬럼 수집
  const newCols = [];
  panel.querySelectorAll("#ed-cols .crow").forEach(row=>{
    const nm=row.querySelector(".cn2").value.trim();
    if(!nm) return;
    newCols.push([nm, row.querySelector(".ct2").value.trim()||"varchar(50)", row.querySelector(".cc2").value.trim(), row.querySelector(".cf2").value]);
  });
  // 관계 수집 (이 테이블이 child 인 것만 갱신)
  const newRels = [];
  panel.querySelectorAll("#ed-rels .rrow").forEach(row=>{
    const target=row.querySelector(".rt2").value;
    const label=row.querySelector(".rl2").value.trim();
    newRels.push([oldId, target, label]);
  });
  // 반영
  const tRow = findTable(oldId);
  tRow[0]=newName; tRow[1]=domain; tRow[2]=key;
  delete COLS[oldId]; COLS[newName]=newCols;
  // R 갱신: oldId 가 child 인 기존 관계 제거 후 재삽입, 그리고 이름 변경 반영
  for(let k=R.length-1;k>=0;k--){ if(R[k][0]===oldId) R.splice(k,1); }
  newRels.forEach(r=>{ r[0]=newName; R.push(r); });
  if(newName!==oldId){ R.forEach(r=>{ if(r[1]===oldId) r[1]=newName; }); }
  selected=newName; renderAll(); setHL(newName); renderEditForm(newName); markDirty();
}
function deleteTable(id){
  const idx=T.findIndex(t=>t[0]===id); if(idx>=0) T.splice(idx,1);
  delete COLS[id];
  for(let k=R.length-1;k>=0;k--){ if(R[k][0]===id||R[k][1]===id) R.splice(k,1); }
  selected=null; renderAll(); panel.style.display="none"; markDirty();
}
function addTable(){
  const name=prompt("새 테이블명을 입력하세요:","new_table");
  if(!name) return;
  if(T.some(t=>t[0]===name)){ alert("이미 존재합니다."); return; }
  const defaultDomain = domainOrder.includes("stat") ? "stat" : domainOrder[0];
  T.push([name,defaultDomain,"새 테이블"]);
  COLS[name]=[[name.replace(/s$/,"")+"_no","int","일련번호","PK"]];
  renderAll(); editMode=true; setEditUI(); selectNode(name); markDirty();
}

// ---------- 서버 저장/불러오기 ----------
const toast = document.getElementById("toast");
let toastTimer = null;
function showToast(msg, cls){
  toast.textContent = msg; toast.className = "toast " + (cls||""); toast.style.display = "block";
  clearTimeout(toastTimer); toastTimer = setTimeout(()=>{ toast.style.display="none"; }, 3000);
}
function schemaDoc(){ return { domains: DOMAINS, tables: T, relations: R, columns: COLS }; }

async function loadFromServer(){
  const res = await fetch(API);
  if(!res.ok) throw new Error(`HTTP ${res.status}`);
  const d = await res.json();
  DOMAINS = d.domains; T = d.tables; R = d.relations; COLS = d.columns;
  selected = null; renderAll(); panel.style.display = "none";
}
async function saveToServer(){
  try {
    const res = await fetch(API, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(schemaDoc()),
    });
    const body = await res.json().catch(()=>({}));
    if(!res.ok) throw new Error(body.error || `HTTP ${res.status}`);
    dirty = false; document.title = "애드콘 ERD";
    showToast(`서버 저장 완료 (테이블 ${body.tables}개, 관계 ${body.relations}개)`, "ok");
  } catch(err){
    showToast("서버 저장 실패: " + err.message, "err");
  }
}

// ---------- 내보내기 / 불러오기 ----------
let dirty=false;
function markDirty(){ dirty=true; document.title="* 애드콘 ERD (편집중)"; }
function download(filename, text){
  const blob=new Blob([text],{type:"text/plain;charset=utf-8"});
  const a=document.createElement("a"); a.href=URL.createObjectURL(blob); a.download=filename; a.click();
  setTimeout(()=>URL.revokeObjectURL(a.href),1000);
}
function exportJSON(){ download("adcon_schema.json", JSON.stringify(schemaDoc(),null,2)); }
function importJSON(){
  const inp=document.createElement("input"); inp.type="file"; inp.accept=".json,application/json";
  inp.onchange=()=>{ const f=inp.files[0]; if(!f) return; const rd=new FileReader();
    rd.onload=()=>{ try{ const d=JSON.parse(rd.result);
      if(d.tables&&d.relations&&d.columns){ if(d.domains) DOMAINS=d.domains; T=d.tables; R=d.relations; COLS=d.columns; selected=null; renderAll(); panel.style.display="none"; markDirty(); showToast("불러오기 완료 — '서버에 저장'을 눌러야 DB에 반영됩니다.","ok"); }
      else alert("형식이 올바르지 않습니다.");
    }catch(e){ alert("JSON 파싱 오류: "+e.message); } };
    rd.readAsText(f); };
  inp.click();
}
function mysqlType(t){ return (t||"varchar(50)").replace(/\buns\b/,"unsigned"); }
function exportSQL(){
  let out="-- 애드콘 ERD 편집본 export\nSET FOREIGN_KEY_CHECKS=0;\n\n";
  T.forEach(t=>{ const id=t[0]; const cols=COLS[id]||[];
    out+=`CREATE TABLE \`${id}\` (\n`;
    const lines=cols.map(c=>`  \`${c[0]}\` ${mysqlType(c[1])}${c[2]?` COMMENT '${String(c[2]).replace(/'/g,"")}'`:""}`);
    const pk=cols.filter(c=>(c[3]||"").includes("PK")).map(c=>`\`${c[0]}\``);
    if(pk.length) lines.push(`  PRIMARY KEY (${pk.join(",")})`);
    out+=lines.join(",\n")+`\n) COMMENT='${String(t[2]||"").replace(/'/g,"")}';\n\n`;
  });
  out+="\n-- 외래키 (관계)\n";
  R.forEach((r,i)=>{ const child=r[0],parent=r[1]; const pcols=COLS[parent]||[]; const ppk=pcols.find(c=>(c[3]||"").includes("PK"));
    const ccols=COLS[child]||[];
    // child 컬럼 추정: parent PK 명과 동일하거나 FK 플래그가 있는 컬럼
    let cc = ppk && ccols.find(c=>c[0]===ppk[0] && (c[3]||"").includes("FK"));
    if(!cc && ppk) cc = ccols.find(c=>c[0]===ppk[0]);
    if(!cc) cc = ccols.find(c=>(c[3]||"").includes("FK"));
    if(cc && ppk) out+=`ALTER TABLE \`${child}\` ADD CONSTRAINT \`fk_${child}_${i}\` FOREIGN KEY (\`${cc[0]}\`) REFERENCES \`${parent}\` (\`${ppk[0]}\`);\n`;
  });
  out+="\nSET FOREIGN_KEY_CHECKS=1;\n";
  download("adcon_schema_edited.sql", out);
}
function exportDBML(){
  let out="// 애드콘 ERD 편집본 (DBML)\n\n";
  T.forEach(t=>{ const id=t[0]; const cols=COLS[id]||[];
    out+=`Table ${id} {\n`;
    cols.forEach(c=>{ const settings=[]; const f=c[3]||"";
      if(f.includes("PK")) settings.push("pk"); if(f==="UK") settings.push("unique");
      if(c[2]) settings.push(`note: '${String(c[2]).replace(/'/g,"")}'`);
      out+=`  ${c[0]} ${(c[1]||"varchar").split(" ")[0]}${settings.length?` [${settings.join(", ")}]`:""}\n`;
    });
    out+=`  Note: '${String(t[2]||"").replace(/'/g,"")}'\n}\n\n`;
  });
  R.forEach(r=>{ const parent=r[1]; const ppk=(COLS[parent]||[]).find(c=>(c[3]||"").includes("PK"));
    const child=r[0]; const ccols=COLS[child]||[]; let cc = ppk && (ccols.find(c=>c[0]===ppk[0]) || ccols.find(c=>(c[3]||"").includes("FK")));
    if(cc&&ppk) out+=`Ref: ${child}.${cc[0]} > ${parent}.${ppk[0]}\n`;
  });
  download("adcon_schema_edited.dbml", out);
}

// ---------- 편집 UI 토글 ----------
const toolbar = document.getElementById("toolbar");
function setEditUI(){
  document.getElementById("editToggle").textContent = editMode ? "✏ 편집 중 (보기로)" : "✏ 편집 모드";
  document.getElementById("editToggle").classList.toggle("on", editMode);
  toolbar.style.display = editMode ? "flex" : "none";
  document.body.classList.toggle("editing", editMode);
}
document.getElementById("editToggle").onclick = () => { editMode=!editMode; setEditUI(); selected=null; clearHL(); };
document.getElementById("tbAdd").onclick = addTable;
document.getElementById("tbSaveServer").onclick = saveToServer;
document.getElementById("tbReload").onclick = async () => {
  if(dirty && !confirm("저장하지 않은 변경사항이 있습니다. 서버 데이터로 덮어쓸까요?")) return;
  try { await loadFromServer(); dirty=false; document.title="애드콘 ERD"; showToast("서버에서 다시 불러왔습니다.","ok"); }
  catch(err){ showToast("불러오기 실패: "+err.message,"err"); }
};
document.getElementById("tbSaveJson").onclick = exportJSON;
document.getElementById("tbLoadJson").onclick = importJSON;
document.getElementById("tbSql").onclick = exportSQL;
document.getElementById("tbDbml").onclick = exportDBML;
window.addEventListener("beforeunload", e => { if(dirty){ e.preventDefault(); e.returnValue=""; } });

// ---------- 최초 로드 ----------
(async function init(){
  try {
    await loadFromServer();
  } catch(err) {
    document.getElementById("subtitle").textContent = "스키마 로드 실패: " + err.message + " (API 서버 상태를 확인하세요)";
    showToast("스키마 로드 실패: " + err.message, "err");
  }
})();
