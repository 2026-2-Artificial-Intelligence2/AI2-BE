# 화면(office-stock-FE)을 빌드해 Caddy에 담는다. 빌드 위치(context)는 office-stock-FE 폴더다.
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM caddy:2-alpine
COPY --from=build /src/dist /srv
