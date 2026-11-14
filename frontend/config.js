// Deployment may replace this file or set window.APP_CONFIG.backendUrl at runtime.
// Empty value uses same-origin (recommended behind a reverse proxy).
// For the standalone local server at :4173, the default points to Spring Boot :8080.
window.APP_CONFIG = window.APP_CONFIG || { backendUrl: "http://localhost:8080" };
