import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import { DATA_DIR, DOWNLOAD_DIR, APP_URL } from "./config.js";

const TOKEN_FILE = path.join(DATA_DIR, "google.json");
const STATE_FILE = path.join(DATA_DIR, "oauth-state.txt");
const SCOPE = "https://www.googleapis.com/auth/drive.file";
const FOLDER_NAME = process.env.DRIVE_FOLDER_NAME || "YT MP3";

export const REDIRECT_URI = `${APP_URL}/api/drive/callback`;

export function driveConfigured() {
  return Boolean(process.env.GOOGLE_CLIENT_ID && process.env.GOOGLE_CLIENT_SECRET);
}

function readToken() {
  try {
    return JSON.parse(fs.readFileSync(TOKEN_FILE, "utf8"));
  } catch {
    return null;
  }
}

function writeToken(t) {
  fs.writeFileSync(TOKEN_FILE, JSON.stringify(t, null, 2), { mode: 0o600 });
}

export async function isDriveConnected() {
  return driveConfigured() && Boolean(readToken()?.refresh_token);
}

export function driveStatus() {
  const t = readToken();
  return {
    configured: driveConfigured(),
    connected: driveConfigured() && Boolean(t?.refresh_token),
    email: t?.email || "",
    folder: FOLDER_NAME,
  };
}

export function disconnectDrive() {
  try {
    fs.unlinkSync(TOKEN_FILE);
  } catch {}
}

export function authUrl() {
  const state = crypto.randomBytes(16).toString("hex");
  fs.writeFileSync(STATE_FILE, state);
  const p = new URLSearchParams({
    client_id: process.env.GOOGLE_CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    response_type: "code",
    scope: `${SCOPE} openid email`,
    access_type: "offline",
    prompt: "consent",
    state,
  });
  return `https://accounts.google.com/o/oauth2/v2/auth?${p}`;
}

export async function handleCallback(code, state) {
  let expected = "";
  try {
    expected = fs.readFileSync(STATE_FILE, "utf8");
    fs.unlinkSync(STATE_FILE);
  } catch {}
  if (!state || state !== expected) throw new Error("Cerere de autentificare invalidă (state).");

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code,
      client_id: process.env.GOOGLE_CLIENT_ID,
      client_secret: process.env.GOOGLE_CLIENT_SECRET,
      redirect_uri: REDIRECT_URI,
      grant_type: "authorization_code",
    }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error_description || data.error || "Schimbul de token a eșuat.");

  let email = "";
  if (data.id_token) {
    try {
      email = JSON.parse(Buffer.from(data.id_token.split(".")[1], "base64url").toString()).email || "";
    } catch {}
  }

  writeToken({
    refresh_token: data.refresh_token,
    access_token: data.access_token,
    expires_at: Date.now() + (data.expires_in - 60) * 1000,
    email,
  });
}

async function accessToken() {
  const t = readToken();
  if (!t?.refresh_token) throw new Error("Google Drive nu este conectat.");
  if (t.access_token && t.expires_at > Date.now()) return t.access_token;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: process.env.GOOGLE_CLIENT_ID,
      client_secret: process.env.GOOGLE_CLIENT_SECRET,
      refresh_token: t.refresh_token,
      grant_type: "refresh_token",
    }),
  });
  const data = await res.json();
  if (!res.ok) {
    if (data.error === "invalid_grant") disconnectDrive();
    throw new Error("Nu am putut reînnoi accesul la Google Drive. Reconectează contul.");
  }
  t.access_token = data.access_token;
  t.expires_at = Date.now() + (data.expires_in - 60) * 1000;
  writeToken(t);
  return t.access_token;
}

async function folderId(token) {
  const t = readToken();
  if (t.folderId) {
    // verificăm că folderul încă există (poate a fost șters)
    const r = await fetch(
      `https://www.googleapis.com/drive/v3/files/${t.folderId}?fields=id,trashed`,
      { headers: { Authorization: `Bearer ${token}` } }
    );
    if (r.ok && !(await r.json()).trashed) return t.folderId;
  }
  const r = await fetch("https://www.googleapis.com/drive/v3/files?fields=id", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ name: FOLDER_NAME, mimeType: "application/vnd.google-apps.folder" }),
  });
  const data = await r.json();
  if (!r.ok) throw new Error(data.error?.message || "Nu am putut crea folderul în Drive.");
  t.folderId = data.id;
  writeToken({ ...readToken(), folderId: data.id });
  return data.id;
}

export async function uploadToDrive(fileName) {
  const filePath = path.join(DOWNLOAD_DIR, path.basename(fileName));
  const token = await accessToken();
  const parent = await folderId(token);
  const stat = await fsp.stat(filePath);

  // 1) pornim o încărcare „resumable”
  const init = await fetch(
    "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,webViewLink",
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json; charset=UTF-8",
        "X-Upload-Content-Type": "audio/mpeg",
        "X-Upload-Content-Length": String(stat.size),
      },
      body: JSON.stringify({ name: path.basename(fileName), parents: [parent] }),
    }
  );
  if (!init.ok) {
    const e = await init.json().catch(() => ({}));
    throw new Error(e.error?.message || `Drive a refuzat încărcarea (${init.status}).`);
  }
  const location = init.headers.get("location");

  // 2) trimitem conținutul fișierului
  const put = await fetch(location, {
    method: "PUT",
    headers: { "Content-Type": "audio/mpeg", "Content-Length": String(stat.size) },
    body: await fsp.readFile(filePath),
  });
  const data = await put.json().catch(() => ({}));
  if (!put.ok) throw new Error(data.error?.message || `Încărcarea a eșuat (${put.status}).`);
  return data;
}
