/**
 * Inkvault sync service (Cloudflare Worker + KV).
 *
 * Stores small named blobs per "space". A space is derived on the phone from the user's sync
 * password, and every blob is encrypted on the phone with a key from that password, so this
 * service only ever sees random-looking ids and encrypted bytes.
 *
 *   GET /v1/<space>/<name>   -> the blob, or 404
 *   PUT /v1/<space>/<name>   -> stores the blob
 *
 * Every request carries X-Sync-Token (also derived from the password). The first write to a
 * space records a hash of it; afterwards only requests with the same token are accepted.
 */
const MAX_BYTES = 5 * 1024 * 1024;
const PATH = /^\/v1\/([0-9a-f]{32})\/([A-Za-z0-9._-]{1,100})$/;

async function sha256Hex(text) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/" || url.pathname === "/health") return new Response("inkvault-sync ok");
    const m = url.pathname.match(PATH);
    if (!m) return new Response("not found", { status: 404 });
    const [, space, name] = m;
    if (name.startsWith("__")) return new Response("reserved name", { status: 400 });

    const token = request.headers.get("x-sync-token") || "";
    if (token.length < 32) return new Response("missing token", { status: 401 });
    const tokenHash = await sha256Hex(token);
    const authKey = `${space}/__auth`;
    const stored = await env.SYNC.get(authKey);

    if (request.method === "GET") {
      if (!stored) return new Response("empty", { status: 404 });
      if (stored !== tokenHash) return new Response("wrong password", { status: 403 });
      const value = await env.SYNC.get(`${space}/${name}`);
      return value === null ? new Response("not found", { status: 404 })
        : new Response(value, { headers: { "content-type": "text/plain; charset=utf-8" } });
    }

    if (request.method === "PUT") {
      if (stored && stored !== tokenHash) return new Response("wrong password", { status: 403 });
      const body = await request.text();
      if (body.length > MAX_BYTES) return new Response("too large", { status: 413 });
      if (!stored) await env.SYNC.put(authKey, tokenHash);
      await env.SYNC.put(`${space}/${name}`, body);
      return new Response(null, { status: 204 });
    }

    return new Response("method not allowed", { status: 405 });
  },
};
