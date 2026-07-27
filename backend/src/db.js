const { Pool } = require('pg');

const pool = new Pool({
  host: process.env.PGHOST || 'localhost',
  port: Number(process.env.PGPORT || 5432),
  user: process.env.PGUSER || 'erd',
  password: process.env.PGPASSWORD || 'erd',
  database: process.env.PGDATABASE || 'adcon_erd',
});

const DDL = `
CREATE TABLE IF NOT EXISTS erd_domain (
  key        TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  color      TEXT NOT NULL,
  sort_order INT  NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS erd_table (
  id          SERIAL PRIMARY KEY,
  name        TEXT NOT NULL UNIQUE,
  domain_key  TEXT NOT NULL REFERENCES erd_domain(key),
  description TEXT NOT NULL DEFAULT '',
  is_hub      BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order  INT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS erd_column (
  id         SERIAL PRIMARY KEY,
  table_id   INT NOT NULL REFERENCES erd_table(id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  col_type   TEXT NOT NULL DEFAULT '',
  comment    TEXT NOT NULL DEFAULT '',
  flag       TEXT NOT NULL DEFAULT '',
  sort_order INT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS erd_relation (
  id              SERIAL PRIMARY KEY,
  child_table_id  INT NOT NULL REFERENCES erd_table(id) ON DELETE CASCADE,
  parent_table_id INT NOT NULL REFERENCES erd_table(id) ON DELETE CASCADE,
  label           TEXT NOT NULL DEFAULT '',
  sort_order      INT NOT NULL DEFAULT 0
);
`;

async function waitForDb(retries = 30, delayMs = 2000) {
  for (let i = 1; i <= retries; i++) {
    try {
      await pool.query('SELECT 1');
      return;
    } catch (err) {
      console.log(`DB 연결 대기 (${i}/${retries}): ${err.message}`);
      await new Promise(r => setTimeout(r, delayMs));
    }
  }
  throw new Error('PostgreSQL에 연결할 수 없습니다.');
}

async function init() {
  await waitForDb();
  await pool.query(DDL);
}

// {domains, tables, relations, columns} 형태의 스키마 문서를 트랜잭션으로 전체 교체
async function replaceSchema(doc) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query('DELETE FROM erd_relation');
    await client.query('DELETE FROM erd_column');
    await client.query('DELETE FROM erd_table');
    await client.query('DELETE FROM erd_domain');

    const domainKeys = Object.keys(doc.domains);
    for (let i = 0; i < domainKeys.length; i++) {
      const k = domainKeys[i];
      const d = doc.domains[k];
      await client.query(
        'INSERT INTO erd_domain (key, name, color, sort_order) VALUES ($1,$2,$3,$4)',
        [k, d.name, d.color, i]
      );
    }

    const tableIds = {};
    for (let i = 0; i < doc.tables.length; i++) {
      const [name, domain, desc, hub] = doc.tables[i];
      const res = await client.query(
        'INSERT INTO erd_table (name, domain_key, description, is_hub, sort_order) VALUES ($1,$2,$3,$4,$5) RETURNING id',
        [name, domain, desc || '', !!hub, i]
      );
      tableIds[name] = res.rows[0].id;
    }

    for (const [tableName, cols] of Object.entries(doc.columns)) {
      const tid = tableIds[tableName];
      if (!tid) continue;
      for (let i = 0; i < cols.length; i++) {
        const [name, type, comment, flag] = cols[i];
        await client.query(
          'INSERT INTO erd_column (table_id, name, col_type, comment, flag, sort_order) VALUES ($1,$2,$3,$4,$5,$6)',
          [tid, name, type || '', comment || '', flag || '', i]
        );
      }
    }

    for (let i = 0; i < doc.relations.length; i++) {
      const [child, parent, label] = doc.relations[i];
      const cid = tableIds[child], pid = tableIds[parent];
      if (!cid || !pid) continue;
      await client.query(
        'INSERT INTO erd_relation (child_table_id, parent_table_id, label, sort_order) VALUES ($1,$2,$3,$4)',
        [cid, pid, label || '', i]
      );
    }

    await client.query('COMMIT');
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();
  }
}

// DB 내용을 프론트엔드가 사용하는 {domains, tables, relations, columns} 형태로 조립
async function loadSchema() {
  const [domains, tables, columns, relations] = await Promise.all([
    pool.query('SELECT * FROM erd_domain ORDER BY sort_order'),
    pool.query('SELECT * FROM erd_table ORDER BY sort_order'),
    pool.query(`SELECT c.*, t.name AS table_name FROM erd_column c
                JOIN erd_table t ON t.id = c.table_id ORDER BY c.table_id, c.sort_order`),
    pool.query(`SELECT r.label, ct.name AS child_name, pt.name AS parent_name FROM erd_relation r
                JOIN erd_table ct ON ct.id = r.child_table_id
                JOIN erd_table pt ON pt.id = r.parent_table_id ORDER BY r.sort_order`),
  ]);

  const doc = { domains: {}, tables: [], relations: [], columns: {} };
  for (const d of domains.rows) doc.domains[d.key] = { name: d.name, color: d.color };
  for (const t of tables.rows) {
    const row = [t.name, t.domain_key, t.description];
    if (t.is_hub) row.push(true);
    doc.tables.push(row);
  }
  for (const c of columns.rows) {
    (doc.columns[c.table_name] = doc.columns[c.table_name] || []).push(
      c.flag ? [c.name, c.col_type, c.comment, c.flag] : [c.name, c.col_type, c.comment]
    );
  }
  for (const r of relations.rows) {
    doc.relations.push(r.label ? [r.child_name, r.parent_name, r.label] : [r.child_name, r.parent_name]);
  }
  return doc;
}

async function isEmpty() {
  const res = await pool.query('SELECT COUNT(*)::int AS cnt FROM erd_table');
  return res.rows[0].cnt === 0;
}

module.exports = { pool, init, replaceSchema, loadSchema, isEmpty };
