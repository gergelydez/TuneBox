/** @type {import('next').NextConfig} */
const nextConfig = {
  // permite deschiderea în modul dev de pe telefon (aceeași rețea Wi-Fi)
  allowedDevOrigins: ["192.168.*.*", "10.*.*.*", "*.local"],
};

export default nextConfig;
