import { createJob } from "@/lib/jobs";
import { ON_VERCEL } from "@/lib/config";

export const runtime = "nodejs";

const QUALITIES = ["128", "192", "256", "320"];

export async function POST(req) {
  if (ON_VERCEL) {
    return Response.json(
      { error: "Pe Vercel descărcarea nu funcționează (lipsesc yt-dlp și ffmpeg). Rulează aplicația pe calculatorul tău sau cu Docker." },
      { status: 501 }
    );
  }
  const body = await req.json().catch(() => ({}));
  const urls = String(body.urls || "")
    .split(/\s+/)
    .map((u) => u.trim())
    .filter(Boolean);

  if (!urls.length) {
    return Response.json({ error: "Lipește cel puțin un link." }, { status: 400 });
  }

  const valid = [];
  for (const u of urls) {
    try {
      const parsed = new URL(u);
      if (!["http:", "https:"].includes(parsed.protocol)) throw 0;
      valid.push(parsed.toString());
    } catch {
      return Response.json({ error: `Link invalid: ${u}` }, { status: 400 });
    }
  }

  const quality = QUALITIES.includes(String(body.quality)) ? String(body.quality) : "192";
  const jobs = valid.map((url) =>
    createJob({ url, quality, playlist: Boolean(body.playlist), toDrive: Boolean(body.toDrive) })
  );
  return Response.json({ jobs: jobs.map((j) => j.id) });
}
