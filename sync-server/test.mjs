// Local check of worker.js with an in-memory KV: node sync-server/test.mjs
import worker from "./worker.js";
import assert from "node:assert";
const kv = new Map();
const env = { SYNC: { get: async (k) => kv.get(k) ?? null, put: async (k, v) => void kv.set(k, v) } };
const space = "0123456789abcdef0123456789abcdef";
const req = (method, name, token, body) => worker.fetch(new Request(`https://x/v1/${space}/${name}`,
  { method, body, headers: token ? { "x-sync-token": token } : {} }), env);
const good = "a".repeat(40), bad = "b".repeat(40);
assert.equal((await req("GET", "manifest.json", good)).status, 404);
assert.equal((await req("PUT", "manifest.json", good, "cipher1")).status, 204);
assert.equal(await (await req("GET", "manifest.json", good)).text(), "cipher1");
assert.equal((await req("GET", "manifest.json", bad)).status, 403);
assert.equal((await req("PUT", "manifest.json", bad, "evil")).status, 403);
assert.equal((await req("GET", "manifest.json", "")).status, 401);
assert.equal((await req("PUT", "__auth", good, "x")).status, 400);
console.log("worker ok");
