# cloud

Purpose: the Remu cloud, a Next.js 15 and TypeScript project on Vercel (bom1) with Supabase Postgres and Supabase Auth in Mumbai (ap-south-1). Nothing here yet; TASK-003 (Phase 0) creates the scaffold.

## What will live here

API routes and webhooks, the Supabase schema with Row Level Security on every table, and the server libraries. Responsibilities: shop accounts and users, device pairing and tokens, the data unlock token, the consent log mirror (no biometrics), nightly hash anchors, WhatsApp Cloud API sends and webhooks (sent, delivered, read, STOP, thumbs-up), member status transitions (`pending_notice` to `active` on delivery, to `withdrawn` on STOP), the FCM relay carrying ids only, signed consent export (PDF and CSV), hub health. It never stores a face image, template or clip, and no route accepts image bytes. The owner web dashboard and Razorpay billing are later phases.

## Planned structure

Route and file names follow TASKS.md and docs/ARCHITECTURE.md section 3.3.

    cloud/
    ├── src/app/api/           health/, shops/, shops/[id]/{devices,staff,consent-export}/,
    │                          devices/{pair,unlock,fcm-token}/, hub/{events,inbox,anchor}/,
    │                          webhooks/whatsapp/, ops/{hubs,metrics}/
    ├── supabase/migrations/   0001_shops.sql onwards, RLS policies beside each table
    ├── src/lib/               supabase.ts, auth.ts, whatsapp.ts, fcm.ts, consentChain.ts, memberState.ts, export.ts
    └── tests/                 Vitest; Playwright joins when the dashboard exists

Withdrawal and feedback go to the hub on the LAN (`POST /members/{id}/remove`, `PATCH /visits/{id}`) and reach the cloud through the outbox, not through a cloud route.

## Commands

`cd cloud && npm run dev`, `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`, `npx playwright test` (later phase). Configuration comes from the Cloud section of `.env.example`, copied to `cloud/.env.local` (never committed). Production values live only in Vercel environment settings.

## Created by

TASK-003 (Phase 0: Project setup) creates the Next.js project with strict TypeScript, Vitest, `GET /api/health` and passing lint, typecheck, test and build. Phase 4 adds the schema, pairing, the consent mirror, WhatsApp and activation. Phase 6 adds withdrawal and export. Phase 7 adds the unlock token service. OPEN: the owner sign-in method for Supabase Auth (phone OTP, which costs SMS and needs DLT registration of the sender and template in India, or email and password), needed before Phase 4.
