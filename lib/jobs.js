import { spawn } from "node:child_process";
import path from "node:path";
import crypto from "node:crypto";
import { DOWNLOAD_DIR, YTDLP_BIN, FFMPEG_DIR } from "./config.js";
import { uploadToDrive, isDriveConnected } from "./drive.js";

const MAX_PARALLEL = 2;

// Păstrăm starea pe globalThis ca să nu se piardă la reîncărcarea codului în modul dev
const store = (globalThis.__ytmp3 ||= { jobs: new Map(), queue: [], running: 0 });

export function listJobs() {
  return [...store.jobs.values()].sort((a, b) => b.createdAt - a.createdAt).slice(0, 30);
}

export function clearFinishedJobs() {
  for (const [id, job] of store.jobs) {
    if (job.status === "done" || job.status === "error") store.jobs.delete(id);
  }
}

export function createJob({ url, quality, playlist, toDrive }) {
  const job = {
    id: crypto.randomUUID(),
    url,
    quality,
    playlist,
    toDrive,
    status: "queued", // queued | running | uploading | done | error
    progress: 0,
    title: "",
    item: "",
    files: [],
    drive: [],
    error: "",
    createdAt: Date.now(),
  };
  store.jobs.set(job.id, job);
  store.queue.push(job.id);
  pump();
  return job;
}

function pump() {
  while (store.running < MAX_PARALLEL && store.queue.length) {
    const job = store.jobs.get(store.queue.shift());
    if (!job) continue;
    store.running++;
    run(job)
      .catch((e) => {
        job.status = "error";
        job.error = e.message || String(e);
      })
      .finally(() => {
        store.running--;
        pump();
      });
  }
}

function run(job) {
  return new Promise((resolve) => {
    job.status = "running";

    const args = [
      "-x",
      "--audio-format", "mp3",
      "--audio-quality", `${job.quality}K`,
      "--embed-metadata",
      "--embed-thumbnail",
      "--convert-thumbnails", "jpg",
      job.playlist ? "--yes-playlist" : "--no-playlist",
      "--newline",
      "--no-colors",
      "--progress",
      "--trim-filenames", "150",
      "-o", path.join(DOWNLOAD_DIR, "%(title)s.%(ext)s"),
      "--progress-template",
      "download:PROG|%(info.playlist_index)s|%(info.n_entries)s|%(info.title)s|%(progress._percent_str)s",
      "--print", "after_move:DONE|%(filepath)s",
    ];
    // YouTube cere un motor JavaScript; folosim chiar Node-ul care rulează aplicația
    args.push("--js-runtimes", `node:${process.execPath}`);
    if (FFMPEG_DIR) args.push("--ffmpeg-location", FFMPEG_DIR);
    args.push("--", job.url);

    let child;
    try {
      child = spawn(YTDLP_BIN, args, { windowsHide: true });
    } catch (e) {
      job.status = "error";
      job.error = `Nu pot porni yt-dlp: ${e.message}`;
      return resolve();
    }

    let stderr = "";
    let buf = "";

    child.stdout.on("data", (chunk) => {
      buf += chunk.toString("utf8");
      const lines = buf.split(/\r?\n/);
      buf = lines.pop();
      for (const line of lines) handleLine(job, line.trim());
    });
    child.stderr.on("data", (chunk) => {
      stderr = (stderr + chunk.toString("utf8")).slice(-4000);
    });

    child.on("error", (e) => {
      job.status = "error";
      job.error =
        e.code === "ENOENT"
          ? "yt-dlp nu este instalat sau nu e în PATH."
          : `Eroare yt-dlp: ${e.message}`;
      resolve();
    });

    child.on("close", async (code) => {
      if (buf) handleLine(job, buf.trim());
      if (job.status === "error") return resolve();

      if (code !== 0 && job.files.length === 0) {
        job.status = "error";
        job.error = lastError(stderr) || `yt-dlp s-a oprit cu codul ${code}`;
        return resolve();
      }
      if (code !== 0) job.error = "Unele piese nu s-au putut descărca.";
      mediaScan(job.files);

      if (job.toDrive && job.files.length) {
        if (!(await isDriveConnected())) {
          job.error = "Google Drive nu este conectat — fișierele au rămas doar pe server.";
        } else {
          job.status = "uploading";
          for (const f of job.files) {
            try {
              const res = await uploadToDrive(f);
              job.drive.push({ file: f, ok: true, link: res.webViewLink });
            } catch (e) {
              job.drive.push({ file: f, ok: false, error: e.message });
            }
          }
        }
      }
      job.status = "done";
      job.progress = 100;
      resolve();
    });
  });
}

function handleLine(job, line) {
  if (line.startsWith("PROG|")) {
    // titlul poate conține „|”, așa că luăm procentul de la final
    const parts = line.split("|");
    const [idx, total] = parts.slice(1, 3);
    const pct = parts.at(-1);
    const title = parts.slice(3, -1).join("|");
    const p = parseFloat(pct);
    const i = parseInt(idx, 10);
    const n = parseInt(total, 10);
    if (title && title !== "NA") job.title = title;
    if (n > 1 && i > 0) {
      job.item = `${i}/${n}`;
      job.progress = Math.min(99, (((i - 1) + (isNaN(p) ? 0 : p / 100)) / n) * 100);
    } else if (!isNaN(p)) {
      job.progress = Math.min(99, p);
    }
  } else if (line.startsWith("DONE|")) {
    const file = path.basename(line.slice(5));
    if (file && !job.files.includes(file)) job.files.push(file);
  }
}

// Pe Android (Termux) anunțăm galeria/player-ul că a apărut o piesă nouă
function mediaScan(files) {
  const cmd = process.env.MEDIA_SCAN_CMD;
  if (!cmd || !files.length) return;
  try {
    const child = spawn(cmd, files.map((f) => path.join(DOWNLOAD_DIR, f)), { stdio: "ignore" });
    child.on("error", () => {});
  } catch {}
}

function lastError(stderr) {
  const lines = stderr.split(/\r?\n/).filter((l) => l.includes("ERROR"));
  return (lines.pop() || "").replace(/^ERROR:\s*/, "").slice(0, 300);
}
