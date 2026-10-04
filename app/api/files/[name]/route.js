import fs from "node:fs";
import fsp from "node:fs/promises";
import { Readable } from "node:stream";
import { safeFilePath } from "@/lib/config";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

async function resolve(params) {
  const { name } = await params;
  let full = safeFilePath(name);
  if (!full) {
    try {
      full = safeFilePath(decodeURIComponent(name));
    } catch {}
  }
  return { name, full };
}

// Descarcă / redă un MP3 (suportă Range, ca să meargă player-ul pe telefon)
export async function GET(req, { params }) {
  const { full } = await resolve(params);
  if (!full) return new Response("Fișierul nu există", { status: 404 });

  const fileName = full.split(/[\\/]/).pop();
  const { size } = await fsp.stat(full);
  const inline = new URL(req.url).searchParams.has("play");
  const headers = {
    "Content-Type": "audio/mpeg",
    "Accept-Ranges": "bytes",
    "Content-Disposition": `${inline ? "inline" : "attachment"}; filename*=UTF-8''${encodeURIComponent(fileName)}`,
  };

  const range = req.headers.get("range");
  const m = range && /bytes=(\d*)-(\d*)/.exec(range);
  if (m) {
    const start = m[1] ? parseInt(m[1], 10) : 0;
    const end = m[2] ? Math.min(parseInt(m[2], 10), size - 1) : size - 1;
    if (start >= size || start > end) {
      return new Response(null, { status: 416, headers: { "Content-Range": `bytes */${size}` } });
    }
    return new Response(Readable.toWeb(fs.createReadStream(full, { start, end })), {
      status: 206,
      headers: { ...headers, "Content-Range": `bytes ${start}-${end}/${size}`, "Content-Length": String(end - start + 1) },
    });
  }

  return new Response(Readable.toWeb(fs.createReadStream(full)), {
    headers: { ...headers, "Content-Length": String(size) },
  });
}

export async function DELETE(req, { params }) {
  const { full } = await resolve(params);
  if (!full) return Response.json({ error: "Fișierul nu există" }, { status: 404 });
  await fsp.unlink(full);
  return Response.json({ ok: true });
}
