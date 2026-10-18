export function allLogs(): string {
  return ((globalThis as { __remuLogLines?: string[] }).__remuLogLines ?? []).join("\n");
}
