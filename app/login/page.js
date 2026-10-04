"use client";

import { useState } from "react";

export default function Login() {
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const res = await fetch("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ password }),
    });
    if (res.ok) window.location.href = "/";
    else {
      setError("Parolă greșită");
      setBusy(false);
    }
  }

  return (
    <main className="login">
      <form className="card" onSubmit={submit}>
        <div className="brand">
          <span className="brand-mark">♪</span> YT → MP3
        </div>
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="Parola"
          autoFocus
          autoComplete="current-password"
        />
        {error && <p className="err-text">{error}</p>}
        <button className="btn primary" disabled={busy}>Intră</button>
      </form>
    </main>
  );
}
