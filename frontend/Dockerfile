# ---- Stage 1: build ----
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install
COPY . .

# VITE_API_BASE_URL se "bake-uieste" in build static la build time.
# Se poate suprascrie prin --build-arg la docker build, sau setand
# VITE_API_BASE_URL in docker-compose.yml -> build.args (vezi docker-compose.yml)
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ---- Stage 2: serve cu nginx ----
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
