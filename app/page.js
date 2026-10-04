"use client";

import { useCallback, useEffect, useRef, useState } from "react";

const QUALITIES = ["128", "192", "256", "320"];

const STATUS = {
  queued: "În așteptare",
  running: "Se descarcă…",
  uploading: "Se încarcă în Drive…",
  done: "Gata",
  error: "Eroare",
};

function fmtSize(b) {
  return b > 1024 * 1024 ? `${(b / 1024 / 1024).toFixed(1)} MB` : `${Math.round(b / 1024)} KB`;
}

async function api(url, opts = {}) {
  const res = await fetch(url, {
    ...opts,
    headers: { "Content-Type": "application/json", ...(opts.headers || {}) },
    cache: "no-store",
  });
  if (res.status === 401) {
    window.location.href = "/login";
    throw new Error("Neautentificat");
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Eroare ${res.status}`);
  return data;
}

// Extrage linkuri dintr-un text (ex. când partajezi din aplicația YouTube)
function extractLinks(text) {
  return (String(text || "").match(/https?:\/\/\S+/g) || []).join("\n");
}

export default function Home() {
  const [urls, setUrls] = useState("");
  const [quality, setQuality] = useState("192");
  const [playlist, setPlaylist] = useState(false);
  const [toDrive, setToDrive] = useState(false);
  const [jobs, setJobs] = useState([]);
  const [files, setFiles] = useState([]);
  const [drive, setDrive] = useState({ configured: false, connected: false });
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState("");
  const [playing, setPlaying] = useState(null);
  const [uploading, setUploading] = useState({});
  const toastTimer = useRef();
  const prevActive = useRef(0);

  const say = useCallback((msg) => {
    setToast(msg);
    clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(""), 3500);
  }, []);

  const loadFiles = useCallback(() => api("/api/files").then((d) => setFiles(d.files)).catch(() => {}), []);
  const loadDrive = useCallback(() => api("/api/drive/status").then(setDrive).catch(() => {}), []);

  // Setări salvate pe dispozitiv + link primit prin „Partajează”
  useEffect(() => {
    try {
      const s = JSON.parse(localStorage.getItem("ytmp3-settings") || "{}");
      if (s.quality) setQuality(s.quality);
      if (typeof s.toDrive === "boolean") setToDrive(s.toDrive);
    } catch {}

    const p = new URLSearchParams(window.location.search);
    const shared = extractLinks([p.get("url"), p.get("text"), p.get("title")].join(" "));
    if (shared) setUrls(shared);

    const d = p.get("drive");
    if (d) say(d === "ok" ? "Google Drive conectat ✓" : `Drive: ${d}`);
    if (shared || d) window.history.replaceState(null, "", "/");

    loadFiles();
    loadDrive();
  }, [loadFiles, loadDrive, say]);

  useEffect(() => {
    try {
      localStorage.setItem("ytmp3-settings", JSON.stringify({ quality, toDrive }));
    } catch {}
  }, [quality, toDrive]);

  // Actualizăm lista de descărcări: des cât timp lucrează ceva, rar în rest
  useEffect(() => {
    let stop = false;
    let t;
    const tick = async () => {
      try {
        const d = await api("/api/jobs");
        if (stop) return;
        setJobs(d.jobs);
        const active = d.jobs.filter((j) => !["done", "error"].includes(j.status)).length;
        if (active < prevActive.current) loadFiles();
        prevActive.current = active;
        t = setTimeout(tick, active ? 1000 : 5000);
      } catch {
        if (!stop) t = setTimeout(tick, 5000);
      }
    };
    tick();
    return () => {
      stop = true;
      clearTimeout(t);
    };
  }, [loadFiles]);

  async function paste() {
    try {
      const text = await navigator.clipboard.readText();
      const links = extractLinks(text);
      if (!links) return say("Nu am găsit niciun link în clipboard");
      setUrls((u) => (u.trim() ? `${u.trim()}\n${links}` : links));
    } catch {
      say("Browserul nu permite citirea clipboard-ului — lipește manual");
    }
  }

  async function start() {
    if (!urls.trim()) return say("Lipește cel puțin un link");
    setBusy(true);
    try {
      await api("/api/download", {
        method: "POST",
        body: JSON.stringify({ urls, quality, playlist, toDrive: toDrive && drive.connected }),
      });
      setUrls("");
      const d = await api("/api/jobs");
      setJobs(d.jobs);
      prevActive.current = d.jobs.filter((j) => !["done", "error"].includes(j.status)).length;
    } catch (e) {
      say(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function sendToDrive(name) {
    setUploading((u) => ({ ...u, [name]: true }));
    try {
      await api("/api/drive/upload", { method: "POST", body: JSON.stringify({ name }) });
      say("Încărcat în Google Drive ✓");
    } catch (e) {
      say(e.message);
    } finally {
      setUploading((u) => ({ ...u, [name]: false }));
    }
  }

  async function remove(name) {
    if (!confirm(`Șterg „${name}” de pe server?`)) return;
    try {
      await api(`/api/files/${encodeURIComponent(name)}`, { method: "DELETE" });
      if (playing === name) setPlaying(null);
      loadFiles();
    } catch (e) {
      say(e.message);
    }
  }

  async function disconnect() {
    if (!confirm("Deconectez Google Drive?")) return;
    await api("/api/drive/disconnect", { method: "POST" }).catch(() => {});
    loadDrive();
  }

  async function clearJobs() {
    await api("/api/jobs", { method: "DELETE" }).catch(() => {});
    const d = await api("/api/jobs").catch(() => ({ jobs: [] }));
    setJobs(d.jobs);
  }

  const fileUrl = (name, play) => `/api/files/${encodeURIComponent(name)}${play ? "?play" : ""}`;

  return (
    <main className="wrap">
      <header className="top">
        <div className="brand">
          <span className="brand-mark">♪</span> YT → MP3
        </div>
        {drive.connected ? (
          <button className="pill" onClick={disconnect} title={drive.email}>
            <span className="dot on" /> Drive
          </button>
        ) : drive.configured ? (
          <a className="pill" href="/api/drive/connect">
            <span className="dot" /> Conectează Drive
          </a>
        ) : null}
      </header>

      <section className="card">
        <h2>
          Linkuri
          <button className="btn small ghost" onClick={paste}>Lipește</button>
        </h2>
        <textarea
          value={urls}
          onChange={(e) => setUrls(e.target.value)}
          placeholder="https://www.youtube.com/watch?v=…&#10;(câte unul pe linie)"
          inputMode="url"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
        />

        <div className="row spread mt">
          <span className="job-meta">Calitate (kbps)</span>
          <div className="seg" role="group" aria-label="Calitate">
            {QUALITIES.map((q) => (
              <button key={q} aria-pressed={quality === q} onClick={() => setQuality(q)}>
                {q}
              </button>
            ))}
          </div>
        </div>

        <div className="mt">
          <label className="toggle">
            <div>
              Tot playlistul
              <small>Dacă linkul face parte dintr-un playlist</small>
            </div>
            <span className="switch">
              <input type="checkbox" checked={playlist} onChange={(e) => setPlaylist(e.target.checked)} />
              <span />
            </span>
          </label>
          {drive.configured && (
            <label className="toggle">
              <div>
                Trimite în Google Drive
                <small>
                  {drive.connected ? `În folderul „${drive.folder}”` : "Conectează mai întâi contul Google"}
                </small>
              </div>
              <span className="switch">
                <input
                  type="checkbox"
                  checked={toDrive && drive.connected}
                  disabled={!drive.connected}
                  onChange={(e) => setToDrive(e.target.checked)}
                />
                <span />
              </span>
            </label>
          )}
        </div>

        <button className="btn primary mt" onClick={start} disabled={busy}>
          {busy ? "Se pornește…" : "Descarcă MP3"}
        </button>
      </section>

      {jobs.length > 0 && (
        <section className="card">
          <h2>
            Descărcări
            <button className="btn small ghost" onClick={clearJobs}>Curăță</button>
          </h2>
          {jobs.map((j) => (
            <div className="job" key={j.id}>
              <div className="job-title">{j.title || j.url}</div>
              <div className={`bar ${j.status === "done" ? "done" : j.status === "error" ? "err" : ""}`}>
                <i style={{ width: `${j.status === "error" ? 100 : Math.round(j.progress)}%` }} />
              </div>
              <div className="job-meta">
                {STATUS[j.status]}
                {j.status === "running" && ` ${Math.round(j.progress)}%`}
                {j.item && j.status === "running" && ` · piesa ${j.item}`}
                {j.status === "done" && j.files.length > 0 && ` · ${j.files.length} fișier${j.files.length > 1 ? "e" : ""}`}
              </div>
              {j.error && <div className={j.status === "error" ? "err-text" : "warn-text"}>{j.error}</div>}
              {j.drive?.length > 0 && (
                <div className={j.drive.every((d) => d.ok) ? "ok-text" : "warn-text"}>
                  Drive: {j.drive.filter((d) => d.ok).length}/{j.drive.length} încărcate
                  {j.drive.find((d) => !d.ok) && ` — ${j.drive.find((d) => !d.ok).error}`}
                </div>
              )}
            </div>
          ))}
        </section>
      )}

      <section className="card">
        <h2>
          Biblioteca ta
          <span>{files.length || ""}</span>
        </h2>
        {files.length === 0 && <div className="empty">Încă nu ai descărcat nimic.</div>}
        {files.map((f) => (
          <div className="file" key={f.name}>
            <button
              className="icon-btn"
              onClick={() => setPlaying(playing === f.name ? null : f.name)}
              aria-label={playing === f.name ? "Oprește" : "Ascultă"}
            >
              {playing === f.name ? "■" : "▶"}
            </button>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="file-name">{f.name.replace(/\.mp3$/i, "")}</div>
              <div className="file-size">{fmtSize(f.size)}</div>
            </div>
            <a className="icon-btn" href={fileUrl(f.name)} download={f.name} aria-label="Salvează pe telefon">
              ⤓
            </a>
            {drive.connected && (
              <button
                className="icon-btn"
                onClick={() => sendToDrive(f.name)}
                disabled={uploading[f.name]}
                aria-label="Trimite în Drive"
              >
                {uploading[f.name] ? "…" : "☁"}
              </button>
            )}
            <button className="icon-btn" onClick={() => remove(f.name)} aria-label="Șterge">
              ✕
            </button>
          </div>
        ))}
      </section>

      {playing && (
        <div className="player">
          <div className="player-name">{playing.replace(/\.mp3$/i, "")}</div>
          <audio key={playing} src={fileUrl(playing, true)} controls autoPlay />
        </div>
      )}

      {toast && <div className="toast">{toast}</div>}
    </main>
  );
}
