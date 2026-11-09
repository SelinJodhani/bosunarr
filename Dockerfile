# syntax=docker/dockerfile:1.7
# Multi-arch (amd64 / arm64). Debian slim so native modules use their prebuilt binaries.

FROM node:22.21-bookworm-slim AS build
# Toolchain for native modules when no prebuilt binary matches (stays out of the final image).
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build && npm prune --omit=dev

FROM node:22.21-bookworm-slim
ENV NODE_ENV=production \
    PORT=8080 \
    BOSUNARR_DATA_DIR=/data \
    BODY_SIZE_LIMIT=1M
WORKDIR /app
COPY --from=build /app/build ./build
COPY --from=build /app/node_modules ./node_modules
COPY package.json ./
RUN mkdir -p /data && chown -R node:node /data
USER node
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD node -e "fetch('http://127.0.0.1:'+process.env.PORT+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "build"]
