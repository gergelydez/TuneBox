// Face aplicația instalabilă pe telefon („Adaugă pe ecranul principal”)
// și o pune în meniul „Partajează” din aplicația YouTube (Android).
export default function manifest() {
  return {
    name: "YT → MP3",
    short_name: "YT MP3",
    description: "Descarcă audio în MP3 și trimite-l în Google Drive",
    start_url: "/",
    display: "standalone",
    background_color: "#121110",
    theme_color: "#121110",
    icons: [
      { src: "/icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" },
      { src: "/icon-192.png", sizes: "192x192", type: "image/png" },
      { src: "/icon-512.png", sizes: "512x512", type: "image/png" },
    ],
    share_target: {
      action: "/",
      method: "GET",
      params: { title: "title", text: "text", url: "url" },
    },
  };
}
