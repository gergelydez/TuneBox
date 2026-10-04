import "./globals.css";

export const metadata = {
  title: "YT → MP3",
  description: "Descarcă audio în MP3 și trimite-l în Google Drive",
  appleWebApp: { capable: true, title: "YT → MP3", statusBarStyle: "black-translucent" },
};

export const viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f6f4f1" },
    { media: "(prefers-color-scheme: dark)", color: "#121110" },
  ],
};

export default function RootLayout({ children }) {
  return (
    <html lang="ro">
      <body>{children}</body>
    </html>
  );
}
