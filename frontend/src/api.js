export function resolveBackendUrl(config = globalThis.APP_CONFIG, locationLike = globalThis.location) {
  const queryOverride = new URLSearchParams(locationLike?.search || "").get("backend");
  const configured = queryOverride || config?.backendUrl || "";
  return configured.replace(/\/$/, "");
}

export function toWebSocketUrl(baseUrl, locationLike = globalThis.location) {
  const base = baseUrl || `${locationLike.protocol}//${locationLike.host}`;
  const parsed = new URL(base, locationLike.href);
  parsed.protocol = parsed.protocol === "https:" ? "wss:" : "ws:";
  parsed.pathname = `${parsed.pathname.replace(/\/$/, "")}/ws/search`;
  parsed.search = "";
  return parsed.toString();
}

export async function request(baseUrl, path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, { ...options, headers: { "Content-Type": "application/json", ...(options.headers || {}) } });
  const text = await response.text();
  let body;
  try { body = text ? JSON.parse(text) : {}; } catch { body = { message: text || "Некоректна відповідь сервера" }; }
  if (!response.ok) throw new Error(body.message || `Backend HTTP ${response.status}`);
  return body;
}

export const api = {
  health: base => request(base, "/api/health"),
  stats: base => request(base, "/api/stats"),
  index: (base, threads) => request(base, "/api/index", { method: "POST", body: JSON.stringify({ threads }) }),
  search: (base, query, mode) => request(base, "/api/search", { method: "POST", body: JSON.stringify({ query, mode }) })
};

export function openSearchSocket(baseUrl, payload, handlers = {}) {
  const socket = new WebSocket(toWebSocketUrl(baseUrl));
  let opened = false;
  socket.addEventListener("open", () => { opened = true; socket.send(JSON.stringify(payload)); handlers.onOpen?.(); });
  socket.addEventListener("message", event => { try { handlers.onEvent?.(JSON.parse(event.data)); } catch { handlers.onError?.(new Error("WebSocket повернув некоректний JSON")); } });
  socket.addEventListener("error", () => handlers.onError?.(new Error("WebSocket-з'єднання недоступне")));
  socket.addEventListener("close", event => handlers.onClose?.(event, opened));
  return { socket, close: () => socket.close() };
}
