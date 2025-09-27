// Centralized configuration for environment variables
// Ensures a safe fallback and normalization (remove trailing slashes)

const rawApiBase = process.env.REACT_APP_API_BASE_URL;

function normalizeBase(url) {
  return url.replace(/\/+$/, ''); // remove trailing slashes
}

export const API_BASE_URL = normalizeBase(
  rawApiBase && rawApiBase.trim().length > 0 ? rawApiBase.trim() : 'http://localhost:8080'
);

if (!rawApiBase) {
  // eslint-disable-next-line no-console
  console.warn('[config] REACT_APP_API_BASE_URL is not set. Using default http://localhost:8080');
}

export function buildApiUrl(path = '') {
  if (!path.startsWith('/')) path = '/' + path;
  return API_BASE_URL + path;
}
