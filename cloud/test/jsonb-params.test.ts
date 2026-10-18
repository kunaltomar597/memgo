import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// postgres.js serialises a parameter typed jsonb with JSON.stringify itself, so passing a
// pre-stringified value to `$n::jsonb` stores a JSON string. Every jsonb parameter must be
// cast through text (`$n::text::jsonb`). Found by tests/integration/test_full_stack.py.
function files(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? files(p) : p.endsWith(".ts") ? [p] : [];
  });
}

describe("jsonb parameters", () => {
  it("are always cast through text", () => {
    const offenders = files(join(__dirname, "..", "src")).filter((f) =>
      /\$\d+::jsonb/.test(readFileSync(f, "utf8")),
    );
    expect(offenders).toEqual([]);
  });
});
