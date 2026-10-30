// Unit tests for the raw response parser used by the interop tests (no server needed).
import { test } from "node:test";
import assert from "node:assert/strict";
import { ResponseParser } from "../src/rawClient.js";

const WIRE =
  "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc" +
  "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;x=y\r\nde\r\n1\r\nf\r\n0\r\nX-T: 1\r\n\r\n";

test("parses fixed and chunked responses from one buffer", () => {
  const p = new ResponseParser();
  p.push(Buffer.from(WIRE, "latin1"));
  const a = p.next();
  const b = p.next();
  assert.equal(a?.body.toString(), "abc");
  assert.equal(b?.body.toString(), "def");
  assert.deepEqual(b?.chunkSizes, [2, 1]);
  assert.equal(b?.trailers.get("x-t"), "1");
  assert.equal(p.next(), null);
});

test("every split point yields the same two responses", () => {
  const bytes = Buffer.from(WIRE, "latin1");
  for (let cut = 0; cut <= bytes.length; cut++) {
    const p = new ResponseParser();
    const got = [];
    p.push(bytes.subarray(0, cut));
    for (let r = p.next(); r !== null; r = p.next()) got.push(r);
    p.push(bytes.subarray(cut));
    for (let r = p.next(); r !== null; r = p.next()) got.push(r);
    assert.deepEqual(got.map((r) => r.body.toString()), ["abc", "def"], `cut at ${cut}`);
  }
});

test("rejects a chunk without its CRLF", () => {
  const p = new ResponseParser();
  p.push(Buffer.from("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nab\r\n0\r\n\r\n", "latin1"));
  assert.throws(() => p.next(), /CRLF/);
});
