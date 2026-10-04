#!/data/data/com.termux/files/usr/bin/bash
# Instalează TuneBox pe Android, în Termux.
#   curl -fsSLo install.sh https://raw.githubusercontent.com/gergelydez/TuneBox/main/termux/install.sh && bash install.sh
set -e

REPO="gergelydez/TuneBox"
RAW="https://raw.githubusercontent.com/$REPO/main/termux"
DIR="$HOME/tunebox"

if [ -z "$PREFIX" ] || [ ! -d "/data/data/com.termux" ]; then
  echo "Scriptul trebuie rulat în aplicația Termux pe Android."; exit 1
fi

echo "==> 1/5 Actualizez Termux și instalez programele (Node.js, Python, ffmpeg)…"
pkg update -y </dev/null
apt-get -y -o Dpkg::Options::="--force-confold" upgrade </dev/null
pkg install -y nodejs-lts python ffmpeg curl jq termux-api </dev/null

echo "==> 2/5 Instalez yt-dlp…"
pip install -U --quiet yt-dlp yt-dlp-ejs mutagen </dev/null

echo "==> 3/5 Acces la memoria telefonului (apasă «Permite» dacă ești întrebat)…"
if [ ! -d "$HOME/storage/shared" ]; then
  termux-setup-storage
  for _ in $(seq 1 60); do [ -d "$HOME/storage/shared" ] && break; sleep 1; done
fi
if [ -d "$HOME/storage/shared" ]; then
  MUSIC="$HOME/storage/shared/Music/TuneBox"
else
  echo "   Fără acces la memorie: piesele rămân în $DIR/downloads"
  MUSIC="$DIR/downloads"
fi
mkdir -p "$DIR/data" "$MUSIC"

if [ ! -f "$DIR/config.env" ]; then
  cat >"$DIR/config.env" <<EOF
# Setările TuneBox. După modificări: tunebox restart
PORT=3000
# 127.0.0.1 = doar de pe telefon. Pune 0.0.0.0 ca s-o deschizi și de pe alte dispozitive din Wi-Fi (setează atunci și parolă).
HOST=127.0.0.1
APP_URL=http://localhost:3000
APP_PASSWORD=
DOWNLOAD_DIR=$MUSIC
DATA_DIR=$DIR/data
MEDIA_SCAN_CMD=termux-media-scan

# Google Drive (opțional) — vezi README, secțiunea Google Drive
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
DRIVE_FOLDER_NAME=TuneBox
EOF
fi

echo "==> 4/5 Descarc aplicația…"
mkdir -p "$PREFIX/bin"
curl -fsSL "$RAW/tunebox" -o "$PREFIX/bin/tunebox"
chmod +x "$PREFIX/bin/tunebox"
tunebox update

echo "==> 5/5 Scurtături…"
# iconiță pe ecranul principal (aplicația Termux:Widget)
mkdir -p "$HOME/.shortcuts"
printf '#!/data/data/com.termux/files/usr/bin/bash\ntunebox open\n' >"$HOME/.shortcuts/TuneBox"
chmod +x "$HOME/.shortcuts/TuneBox"
# pornire automată la repornirea telefonului (aplicația Termux:Boot)
mkdir -p "$HOME/.termux/boot"
printf '#!/data/data/com.termux/files/usr/bin/bash\ntunebox start\n' >"$HOME/.termux/boot/tunebox"
chmod +x "$HOME/.termux/boot/tunebox"
# «Partajează → Termux» dintr-un link YouTube pornește descărcarea
mkdir -p "$HOME/bin"
if [ -f "$HOME/bin/termux-url-opener" ] && ! grep -q tunebox "$HOME/bin/termux-url-opener"; then
  mv "$HOME/bin/termux-url-opener" "$HOME/bin/termux-url-opener.bak"
fi
printf '#!/data/data/com.termux/files/usr/bin/bash\ntunebox share "$1"\n' >"$HOME/bin/termux-url-opener"
chmod +x "$HOME/bin/termux-url-opener"

echo
echo "Gata! Piesele se salvează în: $MUSIC"
echo "Pornesc aplicația…"
tunebox open
