import { COOKIE } from "@/lib/auth";

export async function POST() {
  const res = Response.json({ ok: true });
  res.headers.append("Set-Cookie", `${COOKIE}=; Path=/; Max-Age=0`);
  return res;
}
