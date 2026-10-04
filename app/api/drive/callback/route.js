import { handleCallback } from "@/lib/drive";
import { APP_URL } from "@/lib/config";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(req) {
  const u = new URL(req.url);
  const err = u.searchParams.get("error");
  if (err) return Response.redirect(`${APP_URL}/?drive=${encodeURIComponent(err)}`, 302);
  try {
    await handleCallback(u.searchParams.get("code"), u.searchParams.get("state"));
    return Response.redirect(`${APP_URL}/?drive=ok`, 302);
  } catch (e) {
    return Response.redirect(`${APP_URL}/?drive=${encodeURIComponent(e.message)}`, 302);
  }
}
