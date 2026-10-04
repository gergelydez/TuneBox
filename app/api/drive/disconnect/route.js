import { disconnectDrive } from "@/lib/drive";

export const runtime = "nodejs";

export async function POST() {
  disconnectDrive();
  return Response.json({ ok: true });
}
