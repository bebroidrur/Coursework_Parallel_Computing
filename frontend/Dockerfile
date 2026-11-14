FROM nginx:1.27-alpine

COPY frontend /usr/share/nginx/html
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
# The browser uses same-origin URLs; nginx proxies API and WebSocket traffic
# to the compose service named backend.
RUN printf '%s\n' '// Docker deployment uses the nginx reverse proxy.' \
  'window.APP_CONFIG = { backendUrl: "" };' > /usr/share/nginx/html/config.js

EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
  CMD wget --quiet --output-document=- http://127.0.0.1/ >/dev/null || exit 1
