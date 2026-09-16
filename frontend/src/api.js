/**
 * What the app shows before it has heard from the server.
 *
 * Empty on purpose. The previous default described a backend engineer with three years of
 * experience and senior-role ambitions, and anyone who did not immediately overwrite it got an
 * audit and a roadmap written for a person who did not exist.
 */
export const DEFAULT_PROFILE = {
  id: null,
  fullName: "",
  email: "",
  currentTitle: "",
  industry: "",
  yearOfStudy: null,
  yearsOfExperience: 0,
  bio: "",
  skills: [],
  targetRoles: [],
  targetLocations: [],
  willingToRelocate: false,
  targetWorkType: "ANY",
  rawCvText: "",
  completedMilestones: [],
  roadmapId: null,
};

/** Must match ProfileService.YEAR_OF_STUDY_VALUES on the backend. */
export const YEAR_OF_STUDY_OPTIONS = ['Year 1', 'Year 2', 'Year 3', 'Year 4', 'Year 5+'];

/**
 * Render's free tier stops the backend instance after 15 minutes of inactivity, and the cold start
 * that follows takes roughly 50-60 seconds. The old 8 second timeout could not survive that, so the
 * app declared the backend unreachable on almost every first visit.
 */
export const WAKE_TIMEOUT_MS = 90000;
const DEFAULT_TIMEOUT_MS = 30000;
const AI_TIMEOUT_MS = 75000;

const PROFILE_CACHE_KEY = 'AICAREER_PROFILE_CACHE';

export function getApiBase() {
  const custom = typeof window !== 'undefined' ? localStorage.getItem('AICAREER_BACKEND_URL') : null;
  if (custom && custom.trim()) {
    return normalizeBase(custom.trim());
  }

  const rawBase = import.meta.env.VITE_API_BASE || '';
  if (!rawBase || rawBase === '/api') {
    return '/api';
  }
  return normalizeBase(rawBase);
}

function normalizeBase(value) {
  let url = value.trim();
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    url = 'https://' + url;
  }
  url = url.replace(/\/+$/, '');
  if (!url.endsWith('/api')) {
    url += '/api';
  }
  return url;
}

export function setCustomBackendUrl(url) {
  if (typeof window === 'undefined') return;
  if (!url || !url.trim()) {
    localStorage.removeItem('AICAREER_BACKEND_URL');
  } else {
    localStorage.setItem('AICAREER_BACKEND_URL', url.trim());
  }
}

async function fetchWithTimeout(url, options = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const controller = new AbortController();
  const id = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(id);
  }
}

/**
 * Wraps a request so a sleeping backend does not surface as a failure. The first call to a cold
 * instance routinely times out or returns a gateway error while the container boots; retrying with
 * a short pause turns that into a slow success rather than an error banner.
 */
async function fetchWithRetry(url, options = {}, timeoutMs = DEFAULT_TIMEOUT_MS, attempts = 2) {
  let lastError;
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      const res = await fetchWithTimeout(url, options, timeoutMs);
      if (res.status >= 502 && res.status <= 504 && attempt < attempts - 1) {
        await sleep(2000);
        continue;
      }
      return res;
    } catch (error) {
      lastError = error;
      if (attempt < attempts - 1) {
        await sleep(2000);
      }
    }
  }
  throw lastError;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ---------------------------------------------------------------------------
// Connection
// ---------------------------------------------------------------------------

/**
 * Polls the lightweight health endpoint until the backend answers or the budget runs out.
 * `onProgress` receives the elapsed seconds so the UI can show that the server is waking rather
 * than reporting a failure the user cannot act on.
 */
export async function waitForBackend({ onProgress, budgetMs = WAKE_TIMEOUT_MS } = {}) {
  const startedAt = Date.now();
  let attempt = 0;

  while (Date.now() - startedAt < budgetMs) {
    attempt++;
    try {
      const res = await fetchWithTimeout(`${getApiBase()}/health`, { cache: 'no-store' }, 12000);
      if (res.ok) {
        return true;
      }
    } catch {
      // Expected while the instance is still booting.
    }
    if (onProgress) {
      onProgress({ elapsedSeconds: Math.round((Date.now() - startedAt) / 1000), attempt });
    }
    // Back off gently: a cold start is not helped by hammering it.
    await sleep(Math.min(1500 + attempt * 500, 5000));
  }
  return false;
}

/**
 * Keeps the instance awake while the tab is open. Render spins a free service down after 15
 * minutes idle, so a ping every 10 minutes means an active user never pays the cold start twice.
 */
export function startKeepAlive(intervalMs = 10 * 60 * 1000) {
  if (typeof window === 'undefined') return () => {};
  const id = setInterval(() => {
    if (document.visibilityState === 'visible') {
      fetchWithTimeout(`${getApiBase()}/health`, { cache: 'no-store' }, 10000).catch(() => {});
    }
  }, intervalMs);
  return () => clearInterval(id);
}

// ---------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------

export function readCachedProfile() {
  if (typeof window === 'undefined') return null;
  try {
    const raw = localStorage.getItem(PROFILE_CACHE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/**
 * Writes the browser's copy of the profile.
 *
 * Exported because the caller, not this module, knows whether a response is still relevant. A
 * request that resolves after the profile has moved on is dropped by App — but it used to have
 * already written itself to the cache on the way through, so the next page load restored the very
 * revision that had just been rejected.
 */
export function cacheProfile(profile) {
  if (typeof window === 'undefined' || !profile) return;
  try {
    localStorage.setItem(PROFILE_CACHE_KEY, JSON.stringify(profile));
  } catch {
    // Storage full or disabled; the cache is an optimisation, not a requirement.
  }
}

/**
 * Pass `{ cache: false }` when the response might arrive too late to be relevant; the caller then
 * calls {@link cacheProfile} itself once it has decided to keep it.
 */
export async function fetchCurrentProfile(timeoutMs = WAKE_TIMEOUT_MS, { cache = true } = {}) {
  const res = await fetchWithRetry(`${getApiBase()}/profiles/current`, {}, timeoutMs);
  if (!res.ok) throw new Error('Could not load your profile');
  const profile = await res.json();
  if (cache) cacheProfile(profile);
  return profile;
}

export async function saveProfile(profileData) {
  const res = await fetchWithTimeout(`${getApiBase()}/profiles`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(profileData),
  });
  if (!res.ok) throw new Error('Could not save your profile');
  const profile = await res.json();
  cacheProfile(profile);
  return profile;
}

export async function uploadCvFile(file) {
  const formData = new FormData();
  formData.append('file', file);
  // CV parsing runs a language model over the document, so allow for a slow round trip.
  const res = await fetchWithTimeout(`${getApiBase()}/profiles/upload-cv`, {
    method: 'POST',
    body: formData,
  }, AI_TIMEOUT_MS);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error || 'Could not upload the CV file');
  }
  const profile = await res.json();
  cacheProfile(profile);
  return profile;
}

export async function resetSampleProfile(type) {
  const res = await fetchWithTimeout(`${getApiBase()}/profiles/reset-sample/${type}`, { method: 'POST' });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error || 'Could not switch to the sample profile');
  }
  const profile = await res.json();
  cacheProfile(profile);
  return profile;
}

/**
 * Records one self-reported milestone tick.
 *
 * Its own endpoint rather than a profile save: saving the profile moves the revision, which
 * discards the roadmap the milestone belongs to. The server also validates the milestone against
 * the current roadmap, so a stale tab gets 409/404 instead of writing progress into nothing; the
 * status is attached to the error so the caller can react rather than just showing a message.
 *
 * Deliberately does not touch the browser cache: by the time this resolves the profile may have
 * been replaced, and only the caller can tell. It calls {@link cacheProfile} if it keeps the result.
 */
export async function updateMilestoneProgress(milestoneId, roadmapId, completed) {
  const res = await fetchWithTimeout(
    `${getApiBase()}/profiles/current/milestones/${encodeURIComponent(milestoneId)}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ roadmapId, completed }),
    },
  );
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    const error = new Error(err.error || 'Could not save your progress');
    error.status = res.status;
    throw error;
  }
  return res.json();
}

// ---------------------------------------------------------------------------
// Jobs and matching
// ---------------------------------------------------------------------------

function buildQuery(params) {
  const query = new URLSearchParams();
  if (params.keyword) query.append('keyword', params.keyword);
  if (params.isOverseas !== undefined && params.isOverseas !== null) query.append('isOverseas', params.isOverseas);
  if (params.workType) query.append('workType', params.workType);
  if (params.visaSponsorship !== undefined && params.visaSponsorship !== null) {
    query.append('visaSponsorship', params.visaSponsorship);
  }
  return query.toString();
}

export async function fetchJobs(params = {}) {
  const res = await fetchWithRetry(`${getApiBase()}/jobs?${buildQuery(params)}`);
  if (!res.ok) throw new Error('Could not load the job list');
  return res.json();
}

export async function fetchMatches(params = {}) {
  const res = await fetchWithRetry(`${getApiBase()}/matches?${buildQuery(params)}`);
  if (!res.ok) throw new Error('Could not calculate job matches');
  return res.json();
}

export async function syncExternalJobs() {
  // Five job boards are queried in sequence, which takes a while on a cold instance.
  const res = await fetchWithTimeout(`${getApiBase()}/jobs/sync-external`, { method: 'POST' }, 120000);
  if (!res.ok) throw new Error('Could not sync jobs from the job boards');
  return res.json();
}

// ---------------------------------------------------------------------------
// AI coach
// ---------------------------------------------------------------------------

/**
 * The stored analysis for the current profile. The server generates it once per profile revision
 * and serves the same snapshot afterwards, so calling this again is a reload, not a re-analysis.
 */
export async function fetchProfileAudit() {
  const res = await fetchWithTimeout(`${getApiBase()}/coach/audit`, {}, AI_TIMEOUT_MS);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    const error = new Error(err.error || 'Could not analyse your profile');
    error.status = res.status;
    throw error;
  }
  return res.json();
}

export async function fetchRoadmap() {
  const res = await fetchWithTimeout(`${getApiBase()}/coach/roadmap`, {}, AI_TIMEOUT_MS);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    const error = new Error(err.error || 'Could not load your roadmap');
    error.status = res.status;
    throw error;
  }
  return res.json();
}

export async function sendChatMessage(message, history = []) {
  const res = await fetchWithTimeout(`${getApiBase()}/coach/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, history }),
  }, AI_TIMEOUT_MS);
  if (!res.ok) throw new Error('Could not reach the AI Coach');
  return res.json();
}

export async function fetchJobAiDeepDive(jobId) {
  const res = await fetchWithTimeout(`${getApiBase()}/matches/${jobId}/ai-deep-dive`, {}, AI_TIMEOUT_MS);
  if (!res.ok) throw new Error('Failed to generate AI deep-dive analysis');
  return res.json();
}

/** Reports whether Gemini is actually answering, and why not when it is unavailable. */
export async function fetchAiStatus(probe = false) {
  const res = await fetchWithTimeout(
    `${getApiBase()}/coach/ai-status${probe ? '?probe=true' : ''}`, {}, probe ? AI_TIMEOUT_MS : 15000);
  if (!res.ok) throw new Error('Could not check AI status');
  return res.json();
}
