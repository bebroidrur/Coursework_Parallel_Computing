// Deployment may replace this file or set window.APP_CONFIG.backendUrl at runtime.
// Empty value uses same-origin (recommended behind a reverse proxy).
// For the standalone local server at :4173, the default points to the local
// development backend at :18080. Deployments can override this at runtime.
window.APP_CONFIG = window.APP_CONFIG || { backendUrl: "http://localhost:18080" };
