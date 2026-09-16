/**
 * What the app shows before it has heard from the server.
 *
 * Empty on purpose. A default profile describing an imaginary person is analysed as though it
 * described the person sitting in front of it, and produces a plan for someone who does not exist.
 */
export const DEFAULT_PROFILE = {
  id: null,
  fullName: '',
  email: '',
  phone: '',
  currentTitle: '',
  industry: '',
  yearsOfExperience: 0,
  bio: '',
  skills: [],
  education: '',
  languages: '',
  targetRoles: [],
  rawCvText: '',
  targetRole: '',
  targetSeniority: '',
  targetJobDescription: '',
  planDurationMonths: null,
  planHoursPerWeek: null,
  completedMilestones: [],
  planId: null,
};

/** Must match ProfileService.SENIORITY_VALUES on the backend. */
export const SENIORITY_OPTIONS = [
  { value: 'INTERN', label: 'Internship' },
  { value: 'JUNIOR', label: 'Junior' },
  { value: 'MID', label: 'Mid-level' },
  { value: 'SENIOR', label: 'Senior' },
];

/** Must match ProfileService.DURATION_MONTHS_VALUES. */
export const DURATION_OPTIONS = [1, 3, 6];

/** Must match ProfileService.MAX_HOURS_PER_WEEK. */
export const MAX_HOURS_PER_WEEK = 40;

/**
 * A free hosting tier stops the backend after 15 minutes of inactivity, and the cold start that
 * follows takes roughly 50-60 seconds. A short timeout reported that as "unreachable" on almost
 * every first visit.
 */
export const WAKE_TIMEOUT_MS = 90000;
const DEFAULT_TIMEOUT_MS = 30000;
const AI_TIMEOUT_MS = 90000;

/**
 * Plan generation runs four model calls in sequence, each with its own retry budget. It is the
 * one request in the app that can legitimately take minutes, so it gets its own ceiling rather
 * than being cut off mid-pipeline and reported as a failure the user cannot act on.
 */
const PLAN_TIMEOUT_MS = 300000;

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

/**
 * Every request in the app goes through here, which is why the session cookie is attached here
 * and nowhere else.
 *
 * `credentials: 'include'` is required because the deployed frontend and the API are different
 * origins: without it the browser sends no cookie, the server issues a fresh session on every
 * request, and each call gets a brand-new empty profile. There is no login, so this cookie is the
 * only thing that keeps one person's CV separate from another's.
 */
async function fetchWithTimeout(url, options = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const controller = new AbortController();
  const id = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, credentials: 'include', signal: controller.signal });
  } finally {
    clearTimeout(id);
  }
}

/**
 * Wraps a request so a sleeping backend does not surface as a failure. The first call to a cold
 * instance routinely times out or returns a gateway error while the container boots; retrying
 * after a pause turns that into a slow success rather than an error banner.
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

/**
 * Turns a failed response into an Error carrying what the server said.
 *
 * The server distinguishes "the AI could not be reached" from "the AI answered badly" from "your
 * inputs are incomplete", and each needs a different thing from the user. Collapsing them into one
 * generic message is what made the old UI unactionable.
 */
async function toError(res, fallbackMessage) {
  const body = await res.json().catch(() => ({}));
  const error = new Error(body.error || fallbackMessage);
  error.status = res.status;
  error.detail = body.detail || null;
  error.retryable = Boolean(body.retryable);
  error.missingInputs = body.missingInputs || null;
  return error;
}

// ---------------------------------------------------------------------------
// Connection
// ---------------------------------------------------------------------------

/**
 * Polls the health endpoint until the backend answers or the budget runs out. `onProgress`
 * receives the elapsed seconds so the UI can show that the server is waking rather than reporting
 * a failure the user cannot act on.
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
 * Keeps the instance awake while the tab is open, so an active user pays the cold start once.
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
// Profile and goal
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
 * request that resolves after the profile has moved on is dropped by App - but it used to have
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

export async function fetchCurrentProfile(timeoutMs = WAKE_TIMEOUT_MS, { cache = true } = {}) {
  const res = await fetchWithRetry(`${getApiBase()}/profiles/current`, {}, timeoutMs);
  if (!res.ok) throw await toError(res, 'Could not load your profile');
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
  if (!res.ok) throw await toError(res, 'Could not save your details');
  const profile = await res.json();
  cacheProfile(profile);
  return profile;
}

export async function uploadCvFile(file) {
  const formData = new FormData();
  formData.append('file', file);
  // Extraction runs a language model over the document, so allow for a slow round trip.
  const res = await fetchWithTimeout(`${getApiBase()}/profiles/upload-cv`, {
    method: 'POST',
    body: formData,
  }, AI_TIMEOUT_MS);
  if (!res.ok) throw await toError(res, 'Could not read the CV file');
  const profile = await res.json();
  cacheProfile(profile);
  return profile;
}

export async function resetSampleProfile(type) {
  const res = await fetchWithTimeout(`${getApiBase()}/profiles/reset-sample/${type}`, { method: 'POST' });
  if (!res.ok) throw await toError(res, 'Could not switch to the sample profile');
  const profile = await res.json();
  cacheProfile(profile);
  return profile;
}

// ---------------------------------------------------------------------------
// Learning plan
// ---------------------------------------------------------------------------

/**
 * The stored plan, if there is one. Reading never generates: opening the page must not spend a
 * daily quota on someone who only wanted to look at what they already had.
 */
export async function fetchPlan() {
  const res = await fetchWithRetry(`${getApiBase()}/plan`, {}, WAKE_TIMEOUT_MS);
  if (!res.ok) throw await toError(res, 'Could not load your plan');
  return res.json();
}

/**
 * Runs the pipeline. Minutes, not seconds - the caller shows a real progress state.
 *
 * Pass `force` only when the user deliberately asked to rebuild a plan they already have. Without
 * it the server converges on the stored plan when nothing has changed, which is what two tabs
 * generating at once should do; with it, four model calls produce a replacement. It spends real
 * quota either way, so the distinction is the user's to make, not a default.
 */
export async function generatePlan({ force = false } = {}) {
  const res = await fetchWithTimeout(
    `${getApiBase()}/plan/generate${force ? '?force=true' : ''}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    }, PLAN_TIMEOUT_MS);
  if (!res.ok) throw await toError(res, 'Could not build your plan');
  return res.json();
}

/**
 * Records one self-reported tick.
 *
 * Its own endpoint rather than a profile save: saving the profile moves the revision, which
 * discards the plan the item belongs to. The server also validates the item against the current
 * plan, so a stale tab gets 409/404 instead of writing progress into nothing.
 */
export async function updateProgress(itemId, planId, completed) {
  const res = await fetchWithTimeout(
    `${getApiBase()}/plan/progress/${encodeURIComponent(itemId)}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ planId, completed }),
    },
  );
  if (!res.ok) throw await toError(res, 'Could not save your progress');
  return res.json();
}

/** What reference data the server actually holds: SFIA, the document corpus, the models. */
export async function fetchDataStatus() {
  const res = await fetchWithTimeout(`${getApiBase()}/plan/data-status`, {}, 20000);
  if (!res.ok) throw await toError(res, 'Could not read the data status');
  return res.json();
}

// ---------------------------------------------------------------------------
// Coach chat
// ---------------------------------------------------------------------------

export async function sendChatMessage(message, history = []) {
  const res = await fetchWithTimeout(`${getApiBase()}/coach/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, history }),
  }, AI_TIMEOUT_MS);
  if (!res.ok) throw await toError(res, 'Could not reach the coach');
  return res.json();
}

/** Reports whether Gemini is actually answering, and why not when it is unavailable. */
export async function fetchAiStatus(probe = false) {
  const res = await fetchWithTimeout(
    `${getApiBase()}/coach/ai-status${probe ? '?probe=true' : ''}`, {}, probe ? AI_TIMEOUT_MS : 15000);
  if (!res.ok) throw await toError(res, 'Could not check the AI status');
  return res.json();
}
