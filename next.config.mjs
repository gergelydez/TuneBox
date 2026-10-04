/** @type {import('next').NextConfig} */
const nextConfig = {
  // server de sine stătător (folder .next/standalone) — rulează pe telefon doar cu Node, fără npm install
  output: "standalone",
  // permite deschiderea în modul dev de pe telefon (aceeași rețea Wi-Fi)
  allowedDevOrigins: ["192.168.*.*", "10.*.*.*", "*.local"],
};

export default nextConfig;
