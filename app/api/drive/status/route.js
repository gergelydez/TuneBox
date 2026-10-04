import { driveStatus } from "@/lib/drive";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET() {
  return Response.json(driveStatus());
}
