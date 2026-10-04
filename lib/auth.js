// Parolă opțională pentru aplicație (APP_PASSWORD în .env).
// Cookie-ul conține un hash SHA-256, nu parola.
export const COOKIE = "ytmp3_auth";

export async function hashPassword(pw) {
  const data = new TextEncoder().encode(`ytmp3:${pw}`);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}
