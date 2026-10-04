import { authUrl, driveConfigured } from "@/lib/drive";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET() {
  if (!driveConfigured()) {
    return new Response("Lipsesc GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET în fișierul .env", { status: 500 });
  }
  return Response.redirect(authUrl(), 302);
}
