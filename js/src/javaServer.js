// Starts the Java demo server as a child process and stops it reliably.
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
export const CLASSES = path.resolve(here, "..", "..", "java", "target", "classes");
const MAIN = "portfolio.networkstackfromscratch.http.HttpServerMain";

/**
 * Launch the server on a free loopback port; resolves once it prints its address.
 * @param {number} [idleMs] keep-alive idle timeout in milliseconds
 * @returns {Promise<{host: string, port: number, url: (p: string) => string, stop: () => Promise<void>}>}
 */
export async function startJavaServer(idleMs = 3000) {
  const marker = path.join(CLASSES, ...MAIN.split(".")) + ".class";
  if (!existsSync(marker)) {
    throw new Error(`build the Java server first: cd java && mvn package (${CLASSES} missing)`);
  }
  const child = spawn(
    "java",
    ["-Xmx64m", "-cp", CLASSES, MAIN, "--port", "0", "--idle-ms", String(idleMs), "--exit-on-stdin-eof"],
    { stdio: ["pipe", "pipe", "inherit"] },
  );
  const lines = createInterface({ input: child.stdout });
  const first = await new Promise((resolve, reject) => {
    lines.once("line", resolve);
    child.once("error", reject);
    child.once("exit", (code) => reject(new Error(`server exited early with code ${code}`)));
  });
  const match = /listening on http:\/\/([\d.]+):(\d+)/.exec(String(first));
  if (!match || match[1] === undefined || match[2] === undefined) {
    child.kill();
    throw new Error(`server did not start: ${String(first)}`);
  }
  const host = match[1];
  const port = Number(match[2]);
  return {
    host,
    port,
    url: (p) => `http://${host}:${port}${p}`,
    // Closing stdin makes the server exit; kill it if it has not gone after 5 s.
    stop: () =>
      new Promise((resolve) => {
        if (child.exitCode !== null) {
          resolve();
          return;
        }
        const timer = setTimeout(() => child.kill(), 5000);
        child.once("exit", () => {
          clearTimeout(timer);
          lines.close();
          resolve();
        });
        child.stdin.end();
      }),
  };
}
