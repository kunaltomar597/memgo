import path from "node:path";
import { fileURLToPath } from "node:url";
import type { NextConfig } from "next";

// API-only Next.js app. Functions run in Vercel bom1 (see vercel.json).
const nextConfig: NextConfig = {
  // cloud/ is its own project; do not infer a workspace root from lockfiles further up.
  outputFileTracingRoot: path.dirname(fileURLToPath(import.meta.url)),
  poweredByHeader: false,
  reactStrictMode: true,
  // The PDF export reads the embedded Devanagari font from disk at runtime.
  outputFileTracingIncludes: {
    "/api/shops/[id]/consent-export": ["./assets/fonts/**/*"],
  },
};

export default nextConfig;
