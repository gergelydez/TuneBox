import { NextResponse } from "next/server";
import { COOKIE, hashPassword } from "./lib/auth";

// Dacă APP_PASSWORD e setat, toată aplicația cere autentificare.
export async function proxy(req) {
  const pw = process.env.APP_PASSWORD;
  if (!pw) return NextResponse.next();

  const { pathname } = req.nextUrl;
  if (pathname === "/login" || pathname === "/api/login" || pathname === "/api/drive/callback") {
    return NextResponse.next();
  }

  const cookie = req.cookies.get(COOKIE)?.value;
  if (cookie && cookie === (await hashPassword(pw))) return NextResponse.next();

  if (pathname.startsWith("/api/")) {
    return NextResponse.json({ error: "Neautentificat" }, { status: 401 });
  }
  return NextResponse.redirect(new URL("/login", req.url));
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|.*\\.(?:svg|png|ico|webmanifest)$).*)"],
};
