# YT → MP3

Aplicație web personală (Next.js) care descarcă audio în MP3 cu **yt-dlp**, merge pe telefon și poate trimite fișierele direct în **Google Drive**.

- Lipești unul sau mai multe linkuri (sau un playlist), alegi calitatea 128–320 kbps
- Progres live, bibliotecă cu player, salvare pe telefon, ștergere
- Trimitere în Google Drive (automat după descărcare sau manual, piesă cu piesă)
- Se instalează pe ecranul telefonului ca o aplicație; pe Android apare în meniul **Partajează** din YouTube
- Parolă opțională, ca să n-o poată folosi altcineva

> Aplicația trebuie să ruleze pe un calculator sau server pe care îl controlezi (nu pe Vercel/Netlify): are nevoie de yt-dlp și ffmpeg, iar YouTube blochează de obicei serverele din centre de date. Telefonul doar o deschide în browser.

Folosește-o pentru conținut pe care ai dreptul să-l descarci.

---

## 📲 Aplicația Android (cea mai simplă variantă)

O aplicație normală: o descarci, o instalezi și merge. Fără calculator, fără server, fără Termux.

1. Pe telefon, deschide **[TuneBox.apk](https://github.com/gergelydez/TuneBox/releases/download/android/TuneBox.apk)** și descarcă fișierul.
2. Deschide fișierul descărcat. Android te întreabă dacă permiți instalarea din browser: **Setări → Permite din această sursă**, apoi **Instalează**.
3. Deschide **TuneBox** → tabul **Descarcă**. Lipești un link (sau din YouTube: **Distribuie → TuneBox**), alegi calitatea și apeși **Descarcă MP3**.
4. Tabul **Muzică** e player-ul: piesele din Google Drive sau de pe telefon, căutare, redă tot, amestecă, repetă.

- Fără Drive, piesele ajung în **Music/TuneBox** și apar în orice player de muzică.
- Descărcarea continuă și dacă ieși din aplicație (vezi progresul în notificări).
- yt-dlp se actualizează singur la câteva zile; din meniul ⋮ îl poți actualiza manual dacă descărcările nu mai merg.
- Versiune nouă a aplicației: meniul ⋮ → **Versiune nouă a aplicației**, descarci din nou APK-ul și îl instalezi peste.
- Dacă `TuneBox.apk` nu se instalează pe un telefon foarte vechi, încearcă [TuneBox-32bit.apk](https://github.com/gergelydez/TuneBox/releases/download/android/TuneBox-32bit.apk).

### Muzica ta în Google Drive (opțional, o singură dată, ~10 minute)

Cu Drive conectat, piesele descărcate ajung în folderul **TuneBox** din Google Drive, iar aplicația devine un player: le asculți de acolo (și cu ecranul stins), de pe **orice telefon** pe care instalezi TuneBox și te conectezi cu același cont Google. Ce ai ascultat o dată rămâne în cache, ca să nu se descarce din nou.

Google cere ca aplicația să fie înregistrată într-un proiect Google Cloud al tău (e gratuit):

1. Intră pe [console.cloud.google.com](https://console.cloud.google.com/) cu contul tău Google → sus, **Select a project → New project** → nume `TuneBox` → **Create**.
2. În bara de căutare scrie **Google Drive API** → deschide-l → **Enable**.
3. Meniul ☰ → **APIs & Services → OAuth consent screen** (sau **Google Auth Platform**) → **Get started**:
   nume aplicație `TuneBox`, emailul tău, tip **External**, apoi **Create**.
   La **Audience** apasă **Publish app** (aplicația cere doar acces la fișierele create de ea, deci nu e nevoie de verificare de la Google).
4. **Credentials → Create credentials → OAuth client ID** (sau **Clients → Create client**) → tip **Android**:
   - **Package name:** `ro.tunebox.app`
   - **SHA-1:** `A2:99:91:58:79:B9:6C:41:DE:BC:18:6C:86:86:29:25:A8:2E:25:F3`
     (o găsești și în aplicație: meniul ⋮ → **Despre** → **Copiază SHA-1**)
   - **Create**. Nu trebuie copiat nimic în aplicație.
5. În TuneBox apasă **Conectează Drive** (sus) și alege contul. Gata.

Pe al doilea telefon: instalezi același APK, apeși **Conectează Drive** cu același cont și vezi toate piesele.
Folderul **TuneBox** îl vezi și în aplicația Google Drive sau pe drive.google.com. Aplicația vede doar piesele încărcate de ea (de pe oricare telefon), nu și fișiere puse manual în folder sau restul Drive-ului tău.

Codul aplicației e în folderul `android/`; APK-ul se construiește automat pe GitHub la fiecare modificare.

---

## 📱 Varianta Termux (aplicația web, rulată pe telefon)

Serverul rulează chiar pe telefon, în **Termux**. Piesele ajung în folderul **Music/TuneBox** și apar în orice player de muzică.

**1. Instalează din [F-Droid](https://f-droid.org/)** (nu din Play Store — acolo versiunile nu se potrivesc între ele):
- **Termux** — obligatoriu
- **Termux:Widget** — pentru iconița de pe ecranul principal
- **Termux:API** — ca piesele să apară imediat în player (opțional)
- **Termux:Boot** — ca aplicația să pornească singură după repornirea telefonului (opțional)

**2. Deschide Termux și lipește comanda:**

```bash
curl -fsSLo install.sh https://raw.githubusercontent.com/gergelydez/TuneBox/main/termux/install.sh && bash install.sh
```

Durează câteva minute. Când ești întrebat de acces la fișiere, apasă **Permite**. La final aplicația se deschide în browser.

**3. Iconița pe ecran:** ține apăsat pe ecranul principal → **Widgets** → **Termux:Widget** → alege **TuneBox**.
Apăsarea pe ea pornește serverul (dacă nu merge deja) și deschide aplicația.
Opțional, în Chrome: meniu ⋮ → **Adaugă pe ecranul principal**, ca să se deschidă fără bara browserului (serverul trebuie să fie pornit).

**4. Direct din YouTube:** la un videoclip apasă **Distribuie → Termux**. Aplicația se deschide și descărcarea pornește singură.

### Comenzi utile în Termux

| Comandă | Ce face |
|---|---|
| `tunebox` | pornește și deschide aplicația |
| `tunebox stop` | oprește serverul (economisește bateria) |
| `tunebox update` | actualizează aplicația și yt-dlp — rulează-l când descărcările nu mai merg |
| `tunebox log` | arată erorile serverului |

Setările (parolă, folder, Google Drive) sunt în `~/tunebox/config.env`; după ce le schimbi, rulează `tunebox restart`.
Google Drive merge direct de pe telefon: la redirect URI în Google pune `http://localhost:3000/api/drive/callback`.

Cât timp serverul e pornit, Termux arată o notificare și ține telefonul „treaz”; dacă Android tot oprește Termux, scoate-l din optimizarea bateriei (Setări → Aplicații → Termux → Baterie → Fără restricții).

---

## 1. Pornire rapidă pe calculatorul tău

**Ai nevoie de:** Node.js 20+ , yt-dlp și ffmpeg.

| Sistem | Instalare |
|---|---|
| Windows | `winget install OpenJS.NodeJS.LTS yt-dlp.yt-dlp Gyan.FFmpeg` |
| macOS | `brew install node yt-dlp ffmpeg` |
| Linux | `sudo apt install nodejs npm ffmpeg` + `pip install -U yt-dlp` |

```bash
cd yt-mp3
cp .env.example .env      # pe Windows: copy .env.example .env
npm install
npm run build
npm start
```

Deschide `http://localhost:3000`.

### De pe telefon (aceeași rețea Wi-Fi)

1. Află IP-ul calculatorului (Windows: `ipconfig`, macOS/Linux: `ip a` sau Setări → Wi-Fi), ex. `192.168.1.50`.
2. Pe telefon deschide `http://192.168.1.50:3000`.
3. Din meniul browserului: **Adaugă pe ecranul principal**.

Pe Windows, la prima pornire acceptă cererea firewall-ului pentru „Rețele private”.

---

## 2. Google Drive (opțional)

1. Intră în [Google Cloud Console](https://console.cloud.google.com/), creează un proiect.
2. **APIs & Services → Library** → activează **Google Drive API**.
3. **OAuth consent screen** → tip *External*, completează numele, iar la **Test users** adaugă adresa ta de Gmail.
4. **Credentials → Create credentials → OAuth client ID** → *Web application*.
   La **Authorized redirect URIs** pune exact: `APP_URL` + `/api/drive/callback`, ex.
   `http://localhost:3000/api/drive/callback`
5. Copiază Client ID și Client Secret în `.env`:
   ```
   GOOGLE_CLIENT_ID=...
   GOOGLE_CLIENT_SECRET=...
   APP_URL=http://localhost:3000
   ```
6. Repornește aplicația și apasă **Conectează Drive** (sus, dreapta).

Fișierele ajung în folderul **YT MP3** din Drive. Aplicația are acces doar la fișierele create de ea (permisiunea `drive.file`), nu la restul Drive-ului tău.

**Important:** Google acceptă `http://` doar pentru `localhost`. Ca să conectezi Drive-ul **de pe telefon**, ai nevoie de o adresă `https://` (vezi secțiunea 3). Alternativ: conectezi o singură dată de pe calculator, la `http://localhost:3000` — după asta încărcarea în Drive merge și când folosești aplicația de pe telefon.

---

## 3. Acces de oriunde (opțional)

Cea mai simplă variantă sigură e [Tailscale](https://tailscale.com/) (gratuit): îl instalezi pe calculator și pe telefon, apoi activezi `tailscale serve 3000` și primești o adresă `https://nume.ts.net` accesibilă doar de pe dispozitivele tale.

Altă variantă: **Cloudflare Tunnel**. În ambele cazuri:
- pune adresa nouă în `APP_URL` și adaug-o la redirect URIs în Google;
- **setează `APP_PASSWORD`** în `.env`.

Cu `https://`, pe Android aplicația instalată apare în meniul **Partajează** din YouTube: apeși Share → YT MP3 și linkul se completează singur.

---

## 4. Cu Docker (server acasă, NAS, Raspberry Pi)

```bash
cp .env.example .env    # completează ce ai nevoie
docker compose up -d --build
```

yt-dlp se actualizează automat la fiecare repornire a containerului.

---

## Probleme frecvente

| Problemă | Soluție |
|---|---|
| „yt-dlp nu este instalat” | Instalează-l sau pune calea completă în `YTDLP_PATH` din `.env` |
| Eroare la conversie | ffmpeg lipsește; pe Windows poți pune folderul lui în `FFMPEG_DIR` |
| Descărcările nu mai merg brusc | Actualizează yt-dlp: `yt-dlp -U` sau `pip install -U yt-dlp` |
| „Sign in to confirm you're not a bot” | Apare pe servere din centre de date — rulează aplicația acasă |
| Drive: `redirect_uri_mismatch` | Redirect URI din Google trebuie să fie identic cu `APP_URL/api/drive/callback` |
| Drive: „access blocked” | Adaugă-ți adresa la *Test users* pe ecranul de consimțământ |
