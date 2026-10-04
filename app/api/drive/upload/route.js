import { uploadToDrive } from "@/lib/drive";
import { safeFilePath } from "@/lib/config";

export const runtime = "nodejs";

export async function POST(req) {
  const { name } = await req.json().catch(() => ({}));
  if (!safeFilePath(name)) return Response.json({ error: "Fișierul nu există" }, { status: 404 });
  try {
    const res = await uploadToDrive(name);
    return Response.json({ ok: true, link: res.webViewLink });
  } catch (e) {
    return Response.json({ error: e.message }, { status: 500 });
  }
}
