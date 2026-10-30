// A small HTTP/1.1 response parser over a raw TCP socket, independent of Node's http module,
// so tests can see chunk boundaries, trailers and the order of pipelined responses.
import net from "node:net";

const MAX_BODY = 8 * 1024 * 1024;

/**
 * @typedef {object} RawResponse
 * @property {string} version
 * @property {number} status
 * @property {Map<string, string>} headers lower-case names
 * @property {Buffer} body
 * @property {number[]} chunkSizes
 * @property {Map<string, string>} trailers
 */

/** Incremental parser: push bytes in, take complete responses out. */
export class ResponseParser {
  constructor() {
    /** @type {Buffer} */
    this.buf = Buffer.alloc(0);
  }

  /**
   * Append received bytes.
   * @param {Buffer} data
   */
  push(data) {
    this.buf = Buffer.concat([this.buf, data]);
  }

  /**
   * Parse one complete response from the buffer, or return null if more bytes are needed.
   * @returns {RawResponse | null}
   */
  next() {
    let pos = 0;
    const buf = this.buf;
    /** @returns {string | null} */
    const line = () => {
      const end = buf.indexOf("\r\n", pos);
      if (end < 0) return null;
      const s = buf.subarray(pos, end).toString("latin1");
      pos = end + 2;
      return s;
    };
    /** @returns {Map<string, string> | null} */
    const fields = () => {
      const out = new Map();
      for (;;) {
        const l = line();
        if (l === null) return null;
        if (l === "") return out;
        const colon = l.indexOf(":");
        if (colon <= 0) throw new Error(`bad field line ${JSON.stringify(l)}`);
        out.set(l.slice(0, colon).toLowerCase(), l.slice(colon + 1).trim());
      }
    };
    const statusLine = line();
    if (statusLine === null) return null;
    const m = /^(HTTP\/1\.[01]) (\d{3})/.exec(statusLine);
    if (!m || m[1] === undefined || m[2] === undefined) throw new Error(`bad status line ${statusLine}`);
    const headers = fields();
    if (headers === null) return null;
    /** @type {RawResponse} */
    const resp = {
      version: m[1],
      status: Number(m[2]),
      headers,
      body: Buffer.alloc(0),
      chunkSizes: [],
      trailers: new Map(),
    };
    if (resp.status < 200 || resp.status === 204 || resp.status === 304) {
      this.buf = buf.subarray(pos);
      return resp;
    }
    if ((headers.get("transfer-encoding") ?? "").toLowerCase() === "chunked") {
      /** @type {Buffer[]} */
      const parts = [];
      for (;;) {
        const sizeLine = line();
        if (sizeLine === null) return null;
        const size = parseInt(sizeLine.split(";")[0] ?? "", 16);
        if (Number.isNaN(size)) throw new Error(`bad chunk size ${sizeLine}`);
        if (size === 0) break;
        if (pos + size + 2 > buf.length) return null;
        parts.push(buf.subarray(pos, pos + size));
        resp.chunkSizes.push(size);
        if (buf.subarray(pos + size, pos + size + 2).toString("latin1") !== "\r\n") {
          throw new Error("chunk not followed by CRLF");
        }
        pos += size + 2;
      }
      const trailers = fields();
      if (trailers === null) return null;
      resp.trailers = trailers;
      resp.body = Buffer.concat(parts);
    } else {
      const length = Number(headers.get("content-length"));
      if (!Number.isInteger(length) || length < 0 || length > MAX_BODY) {
        throw new Error("missing or invalid Content-Length");
      }
      if (pos + length > buf.length) return null;
      resp.body = Buffer.from(buf.subarray(pos, pos + length));
      pos += length;
    }
    this.buf = buf.subarray(pos);
    return resp;
  }
}

/**
 * Send raw bytes on one connection and collect `count` responses.
 * @param {string} host
 * @param {number} port
 * @param {string | Buffer} raw
 * @param {number} count
 * @returns {Promise<RawResponse[]>}
 */
export function pipeline(host, port, raw, count) {
  return new Promise((resolve, reject) => {
    const parser = new ResponseParser();
    /** @type {RawResponse[]} */
    const out = [];
    const sock = net.connect({ host, port }, () => sock.write(raw));
    sock.setTimeout(5000, () => sock.destroy(new Error("timeout")));
    sock.on("data", (d) => {
      parser.push(d);
      try {
        for (let r = parser.next(); r !== null; r = parser.next()) {
          out.push(r);
        }
      } catch (e) {
        sock.destroy();
        reject(e);
        return;
      }
      if (out.length >= count) {
        sock.end();
        resolve(out);
      }
    });
    sock.on("error", reject);
    sock.on("close", () => {
      if (out.length < count) reject(new Error(`connection closed after ${out.length} responses`));
    });
  });
}
