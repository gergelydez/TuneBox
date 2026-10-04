import { listJobs, clearFinishedJobs } from "@/lib/jobs";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET() {
  return Response.json({ jobs: listJobs() });
}

export async function DELETE() {
  clearFinishedJobs();
  return Response.json({ ok: true });
}
