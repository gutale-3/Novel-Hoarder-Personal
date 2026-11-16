// Runs worker.js locally with an in-memory store: node sync-server/local-server.mjs [port]
import http from "node:http";
import worker from "./worker.js";
const kv = new Map();
const env = { SYNC: { get: async (k) => kv.get(k) ?? null, put: async (k, v) => void kv.set(k, v) } };
const port = Number(process.argv[2] || 8787);
http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;
  const r = await worker.fetch(new Request(`http://localhost:${port}${req.url}`,
    { method: req.method, headers: req.headers, body: req.method === "GET" ? undefined : body }), env);
  res.writeHead(r.status, Object.fromEntries(r.headers));
  res.end(Buffer.from(await r.arrayBuffer()));
}).listen(port, () => console.log("listening " + port));
