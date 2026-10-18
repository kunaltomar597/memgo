/**
 * A fixed-window in-memory limiter for hot, unauthenticated paths where a database write per
 * request would itself be the load (the WhatsApp webhook). Per function instance, so it is a
 * coarse flood guard, not an exact quota. Memory is bounded by maxKeys.
 */
export class WindowLimiter {
  private readonly hits = new Map<string, { start: number; count: number }>();

  constructor(
    readonly limit: number,
    readonly windowMs: number,
    readonly maxKeys = 10_000,
  ) {}

  /** Counts one request for key; false when the key is over the limit in the current window. */
  hit(key: string, nowMs: number): boolean {
    let w = this.hits.get(key);
    if (!w || nowMs - w.start >= this.windowMs || nowMs < w.start) {
      if (!w && this.hits.size >= this.maxKeys) this.prune(nowMs);
      w = { start: nowMs, count: 0 };
      this.hits.set(key, w);
    }
    w.count++;
    return w.count <= this.limit;
  }

  private prune(nowMs: number): void {
    for (const [k, w] of this.hits) if (nowMs - w.start >= this.windowMs) this.hits.delete(k);
    if (this.hits.size >= this.maxKeys) this.hits.clear();
  }
}

/** POST /api/webhooks/whatsapp: 50 requests per second per client address. */
export const WEBHOOK_RATE_LIMIT = { limit: 50, windowMs: 1000 } as const;
export const webhookLimiter = new WindowLimiter(WEBHOOK_RATE_LIMIT.limit, WEBHOOK_RATE_LIMIT.windowMs);
