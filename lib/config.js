import path from "node:path";
import fs from "node:fs";
import os from "node:os";

// Pe Vercel doar /tmp se poate scrie (și nu e păstrat între cereri)
export const ON_VERCEL = Boolean(process.env.VERCEL);
const BASE = ON_VERCEL ? path.join(os.tmpdir(), "ytmp3") : ".";

// Unde se salvează MP3-urile și datele aplicației (token Google etc.)
export const DOWNLOAD_DIR = path.resolve(/*turbopackIgnore: true*/ process.env.DOWNLOAD_DIR || path.join(BASE, "downloads"));
export const DATA_DIR = path.resolve(/*turbopackIgnore: true*/ process.env.DATA_DIR || path.join(BASE, "data"));

// Programele externe (pot fi schimbate din .env dacă nu sunt în PATH)
export const YTDLP_BIN = process.env.YTDLP_PATH || "yt-dlp";
export const FFMPEG_DIR = process.env.FFMPEG_DIR || ""; // folderul în care e ffmpeg, dacă nu e în PATH

// Adresa publică a aplicației (folosită pentru autentificarea Google)
export const APP_URL = (process.env.APP_URL || "http://localhost:3000").replace(/\/$/, "");

for (const dir of [DOWNLOAD_DIR, DATA_DIR]) {
  fs.mkdirSync(dir, { recursive: true });
}

// Numele de fișier e acceptat doar dacă e un MP3 simplu din folderul de descărcări
export function safeFilePath(name) {
  const base = path.basename(name || "");
  if (!base || base !== name || !base.toLowerCase().endsWith(".mp3")) return null;
  const full = path.join(DOWNLOAD_DIR, base);
  return fs.existsSync(full) ? full : null;
}
