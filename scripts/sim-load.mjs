#!/usr/bin/env node
// ERD Studio 부하 시뮬레이션 — Node 21+ 내장 WebSocket 사용, 외부 의존성 없음.
//
// 사용:
//   node scripts/sim-load.mjs --url ws://localhost:8080/ws --clients 30 --ops-per-sec 5 --duration 60
//
// 시험용 테이블 1개를 만들어 table.move op를 초당 N회 보내고,
// 모든 클라이언트의 수신 왕복 지연을 측정한다.
// 통과 기준: p95 ≤ 1000ms, 수신 누락 0건 → exit 0

function parseArgs() {
  const out = { url: "ws://localhost:8080/ws", clients: 30, opsPerSec: 5, duration: 60 };
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i].replace(/^--/, "");
    const value = argv[i + 1];
    if (key === "url") out.url = value;
    if (key === "clients") out.clients = Number(value);
    if (key === "ops-per-sec") out.opsPerSec = Number(value);
    if (key === "duration") out.duration = Number(value);
  }
  return out;
}

const args = parseArgs();
const httpBase = args.url.replace(/^ws/, "http").replace(/\/ws$/, "");
const TEST_TABLE = `sim_load_${process.pid}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function connect(index, sentAt, latencies, counter) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(args.url);
    const timeout = setTimeout(() => reject(new Error(`클라이언트 ${index} 접속 시간 초과`)), 10000);
    ws.onopen = () => {
      clearTimeout(timeout);
      ws.send(JSON.stringify({ kind: "hello", user: `sim${String(index).padStart(2, "0")}`, color: "#4f8cff" }));
      resolve(ws);
    };
    ws.onerror = (e) => reject(new Error(`클라이언트 ${index} 오류: ${e.message ?? "unknown"}`));
    ws.onmessage = (ev) => {
      const msg = JSON.parse(String(ev.data));
      if (msg.kind !== "op" || msg.op.type !== "table.move" || msg.op.payload.name !== TEST_TABLE) return;
      const t0 = sentAt.get(msg.op.payload.x);
      if (t0 !== undefined) {
        latencies.push(performance.now() - t0);
        counter.received++;
      }
    };
  });
}

async function main() {
  const schema = await fetch(`${httpBase}/api/schema`).then((r) => r.json());
  const domain = Object.keys(schema.domains)[0];
  const sentAt = new Map();
  const latencies = [];
  const counter = { received: 0 };

  console.log(`접속 중: ${args.clients}개 클라이언트 → ${args.url}`);
  const clients = await Promise.all(
    Array.from({ length: args.clients }, (_, i) => connect(i + 1, sentAt, latencies, counter)),
  );

  clients[0].send(JSON.stringify({
    kind: "op",
    op: { type: "table.add", user: "sim", payload: { name: TEST_TABLE, domain, desc: "부하 시험용" } },
  }));
  await sleep(1000);

  console.log(`발신 시작: 초당 ${args.opsPerSec} op × ${args.duration}초`);
  let sent = 0;
  const intervalMs = 1000 / args.opsPerSec;
  const endAt = performance.now() + args.duration * 1000;
  while (performance.now() < endAt) {
    const key = 100000 + sent;
    sentAt.set(key, performance.now());
    clients[sent % clients.length].send(JSON.stringify({
      kind: "op",
      op: { type: "table.move", user: "sim", payload: { name: TEST_TABLE, x: key, y: 100 } },
    }));
    sent++;
    await sleep(intervalMs);
  }

  await sleep(3000); // 수신 유예
  clients[0].send(JSON.stringify({
    kind: "op",
    op: { type: "table.delete", user: "sim", payload: { name: TEST_TABLE } },
  }));
  await sleep(500);
  clients.forEach((ws) => ws.close());

  const expected = sent * args.clients;
  const missing = expected - counter.received;
  latencies.sort((a, b) => a - b);
  const pct = (p) => latencies[Math.min(latencies.length - 1, Math.floor(latencies.length * p))] ?? Infinity;
  const p50 = pct(0.5).toFixed(1);
  const p95 = pct(0.95);
  const max = (latencies[latencies.length - 1] ?? Infinity).toFixed(1);

  console.log(`발신 op: ${sent}건 / 기대 수신: ${expected}건 / 실제 수신: ${counter.received}건 / 누락: ${missing}건`);
  console.log(`지연(ms): p50=${p50} p95=${p95.toFixed(1)} max=${max}`);
  const pass = p95 <= 1000 && missing === 0;
  console.log(pass ? "PASS (p95 ≤ 1000ms, 누락 0건)" : "FAIL");
  process.exit(pass ? 0 : 1);
}

main().catch((e) => {
  console.error("시뮬레이션 실패:", e.message);
  process.exit(1);
});
