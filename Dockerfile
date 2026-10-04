FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:22-bookworm-slim
WORKDIR /app
# ffmpeg pentru conversie + yt-dlp (ultima versiune)
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg python3 ca-certificates curl \
 && curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
 && chmod +x /usr/local/bin/yt-dlp \
 && apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*
ENV NODE_ENV=production DOWNLOAD_DIR=/app/downloads DATA_DIR=/app/data
COPY --from=build /app/package*.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/next.config.mjs ./
EXPOSE 3000
# La fiecare pornire actualizăm yt-dlp (YouTube se schimbă des)
CMD ["sh", "-c", "yt-dlp -U || true; npm start"]
