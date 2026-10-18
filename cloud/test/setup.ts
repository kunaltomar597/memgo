import { beforeEach } from "vitest";
import { setLogSink } from "@/lib/log";

/** Every log line of the current test file, for PII assertions; nothing is printed. */
export const logLines: string[] = [];
(globalThis as { __remuLogLines?: string[] }).__remuLogLines = logLines;
setLogSink((l) => logLines.push(l));
beforeEach(() => {
  setLogSink((l) => logLines.push(l));
});
