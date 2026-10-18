type Handler = (req: Request, ctx: { params: Promise<Record<string, string>> }) => Promise<Response>;
export type RouteModule = Partial<Record<"GET" | "POST" | "PUT" | "PATCH" | "DELETE", unknown>>;

export interface CallOptions {
  path?: string;
  body?: unknown;
  raw?: string;
  headers?: Record<string, string>;
  token?: string;
  params?: Record<string, string>;
}

/** Calls a Next.js route handler the way the runtime does. */
export async function call(mod: RouteModule, method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE", o: CallOptions = {}) {
  const handler = mod[method] as Handler | undefined;
  if (!handler) throw new Error(`no ${method} handler`);
  const headers: Record<string, string> = { ...(o.headers ?? {}) };
  let body: string | undefined;
  if (o.raw !== undefined) body = o.raw;
  else if (o.body !== undefined) body = JSON.stringify(o.body);
  if (body !== undefined && !Object.keys(headers).some((k) => k.toLowerCase() === "content-type")) {
    headers["content-type"] = "application/json";
  }
  if (o.token) headers.authorization = `Bearer ${o.token}`;
  const req = new Request(`http://localhost${o.path ?? "/"}`, { method, headers, body });
  const res = await handler(req, { params: Promise.resolve(o.params ?? {}) });
  const text = method === "GET" || res.headers.get("content-type")?.includes("json") ? await res.clone().text() : "";
  let json: unknown = undefined;
  try {
    json = text ? JSON.parse(text) : undefined;
  } catch {
    json = undefined;
  }
  return { res, status: res.status, json: json as Record<string, unknown> & unknown[] & Record<string, never> };
}
