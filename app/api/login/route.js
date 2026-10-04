import { COOKIE, hashPassword } from "@/lib/auth";

export const runtime = "nodejs";

export async function POST(req) {
  const { password } = await req.json().catch(() => ({}));
  const expected = process.env.APP_PASSWORD;
  if (!expected || password !== expected) {
    await new Promise((r) => setTimeout(r, 800));
    return Response.json({ error: "Parolă greșită" }, { status: 401 });
  }
  const res = Response.json({ ok: true });
  res.headers.append(
    "Set-Cookie",
    `${COOKIE}=${await hashPassword(expected)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${60 * 60 * 24 * 180}`
  );
  return res;
}
