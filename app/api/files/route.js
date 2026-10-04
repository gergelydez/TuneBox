import fs from "node:fs/promises";
import path from "node:path";
import { DOWNLOAD_DIR } from "@/lib/config";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET() {
  const names = (await fs.readdir(DOWNLOAD_DIR)).filter((n) => n.toLowerCase().endsWith(".mp3"));
  const files = await Promise.all(
    names.map(async (name) => {
      const s = await fs.stat(path.join(DOWNLOAD_DIR, name));
      return { name, size: s.size, mtime: s.mtimeMs };
    })
  );
  files.sort((a, b) => b.mtime - a.mtime);
  return Response.json({ files });
}
