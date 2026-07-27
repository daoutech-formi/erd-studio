const express = require('express');
const fs = require('fs');
const path = require('path');
const db = require('./db');

const app = express();
const PORT = Number(process.env.PORT || 3000);

app.use(express.json({ limit: '10mb' }));

app.get('/api/health', (req, res) => res.json({ ok: true }));

app.get('/api/schema', async (req, res) => {
  try {
    res.json(await db.loadSchema());
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: err.message });
  }
});

function validateDoc(doc) {
  if (!doc || typeof doc !== 'object') return '요청 본문이 없습니다.';
  if (!doc.domains || typeof doc.domains !== 'object') return 'domains가 없습니다.';
  if (!Array.isArray(doc.tables)) return 'tables가 배열이 아닙니다.';
  if (!Array.isArray(doc.relations)) return 'relations가 배열이 아닙니다.';
  if (!doc.columns || typeof doc.columns !== 'object') return 'columns가 없습니다.';
  for (const t of doc.tables) {
    if (!Array.isArray(t) || !t[0] || !doc.domains[t[1]]) return `잘못된 테이블 항목: ${JSON.stringify(t)}`;
  }
  const names = new Set(doc.tables.map(t => t[0]));
  if (names.size !== doc.tables.length) return '테이블명이 중복되었습니다.';
  for (const r of doc.relations) {
    if (!Array.isArray(r) || !names.has(r[0]) || !names.has(r[1])) return `잘못된 관계 항목: ${JSON.stringify(r)}`;
  }
  return null;
}

app.put('/api/schema', async (req, res) => {
  const errMsg = validateDoc(req.body);
  if (errMsg) return res.status(400).json({ error: errMsg });
  try {
    await db.replaceSchema(req.body);
    res.json({ ok: true, tables: req.body.tables.length, relations: req.body.relations.length });
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: err.message });
  }
});

async function main() {
  await db.init();
  if (await db.isEmpty()) {
    const seedPath = path.join(__dirname, '..', 'seed', 'schema.json');
    if (fs.existsSync(seedPath)) {
      const seed = JSON.parse(fs.readFileSync(seedPath, 'utf8'));
      await db.replaceSchema(seed);
      console.log(`시드 데이터 적재 완료: 테이블 ${seed.tables.length}개, 관계 ${seed.relations.length}개`);
    } else {
      console.log('시드 파일이 없어 빈 스키마로 시작합니다.');
    }
  }
  app.listen(PORT, () => console.log(`API 서버 시작: http://0.0.0.0:${PORT}`));
}

main().catch(err => {
  console.error('서버 시작 실패:', err);
  process.exit(1);
});
