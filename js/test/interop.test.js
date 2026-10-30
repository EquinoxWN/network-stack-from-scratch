// Node.js clients against the Java HTTP/1.1 server: keep-alive agent, chunked upload and download,
// fetch, and byte-level pipelining checks.
import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { startJavaServer } from "../src/javaServer.js";
import { pipeline } from "../src/rawClient.js";

/** @type {Awaited<ReturnType<typeof startJavaServer>>} */
let server;

before(async () => {
  server = await startJavaServer();
});

after(async () => {
  await server.stop();
});

/**
 * One request through node:http; resolves with status, headers, body and whether the socket was reused.
 * @param {http.RequestOptions} options
 * @param {(req: http.ClientRequest) => void} [send]
 * @returns {Promise<{status: number, headers: http.IncomingHttpHeaders, body: Buffer, reused: boolean}>}
 */
function request(options, send) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: server.host, port: server.port, ...options }, (res) => {
      /** @type {Buffer[]} */
      const parts = [];
      res.on("data", (c) => parts.push(c));
      res.on("end", () =>
        resolve({ status: res.statusCode ?? 0, headers: res.headers, body: Buffer.concat(parts), reused: req.reusedSocket }),
      );
      res.on("error", reject);
    });
    req.on("error", reject);
    if (send) send(req);
    else req.end();
  });
}

test("keep-alive agent sends 30 requests over one connection", async () => {
  const agent = new http.Agent({ keepAlive: true, maxSockets: 1 });
  const ids = new Set();
  let reused = 0;
  for (let i = 1; i <= 30; i++) {
    const r = await request({ path: "/conn", agent });
    assert.equal(r.status, 200);
    assert.equal(r.headers["x-request-number"], String(i));
    ids.add(r.headers["x-connection-id"]);
    if (r.reused) reused++;
  }
  agent.destroy();
  assert.equal(ids.size, 1);
  assert.equal(reused, 29);
});

test("chunked upload written in pieces is echoed back", async () => {
  const r = await request(
    { method: "POST", path: "/echo", headers: { "Content-Type": "text/plain" } },
    (req) => {
      // No Content-Length: node:http frames the body with Transfer-Encoding: chunked.
      req.write("alpha,");
      req.write("beta,");
      req.end("gamma");
    },
  );
  assert.equal(r.status, 200);
  assert.equal(r.headers["x-request-framing"], "chunked");
  assert.equal(r.body.toString(), "alpha,beta,gamma");
});

test("chunked download is decoded by node:http", async () => {
  const r = await request({ path: "/stream?chunks=250&size=400" });
  assert.equal(r.headers["transfer-encoding"], "chunked");
  assert.equal(r.body.length, 100_000);
});

test("fetch (undici) reads chunked and fixed-length bodies", async () => {
  const res = await fetch(server.url("/stream?chunks=3&size=5"));
  assert.equal(await res.text(), "abcdebcdefcdefg");
  const echo = await fetch(server.url("/echo"), { method: "POST", body: new Uint8Array([0, 1, 2, 255]) });
  assert.deepEqual([...new Uint8Array(await echo.arrayBuffer())], [0, 1, 2, 255]);
});

test("pipelined requests on a raw socket come back in order", async () => {
  const raw =
    "GET /conn HTTP/1.1\r\nHost: x\r\n\r\n" +
    "POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhi\r\n0\r\n\r\n" +
    "GET /stream?chunks=2&size=3 HTTP/1.1\r\nHost: x\r\nTE: trailers\r\n\r\n" +
    "GET /conn HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n";
  const rs = await pipeline(server.host, server.port, raw, 4);
  assert.deepEqual(rs.map((r) => r.status), [200, 200, 200, 200]);
  assert.match(rs[0]?.body.toString() ?? "", /request=1\n$/);
  assert.equal(rs[1]?.body.toString(), "hi");
  assert.deepEqual(rs[2]?.chunkSizes, [3, 3]);
  assert.equal(rs[2]?.trailers.get("x-byte-sum"), String([..."abcbcd"].reduce((s, c) => s + c.charCodeAt(0), 0)));
  assert.match(rs[3]?.body.toString() ?? "", /request=4\n$/);
  assert.equal(rs[3]?.headers.get("connection"), "close");
});

test("smuggling-style request is rejected with 400 and close", async () => {
  const raw = "POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n";
  const [r] = await pipeline(server.host, server.port, raw, 1);
  assert.equal(r?.status, 400);
  assert.equal(r?.headers.get("connection"), "close");
});
