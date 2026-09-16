/**
 * Practice sessions: the small amount of shared vocabulary between the roadmap, the job deep-dive
 * and the coach chat.
 *
 * A practice request is created by whichever view the user clicked in, handed to App, and consumed
 * exactly once by the chat. The `requestId` is what makes "exactly once" true: React StrictMode
 * mounts effects twice in development, and without an id to tick off, every practice click sent
 * the opening prompt twice and the coach answered its own question.
 */

/** Identifies the profile content the session was started against. */
export function profileRevisionOf(profile) {
  if (!profile) return 'none';
  return `${profile.id ?? 'new'}@${profile.updatedAt ?? 'new'}`;
}

function newId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return `id-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function createRoadmapPracticeRequest(profile, milestone, roadmapId) {
  return {
    requestId: newId(),
    source: 'roadmap',
    profileRevision: profileRevisionOf(profile),
    roadmapId: roadmapId ?? null,
    milestoneId: milestone?.id ?? null,
    milestoneTitle: milestone?.title ?? '',
    milestoneDescription: milestone?.description ?? '',
    category: milestone?.category ?? null,
    jobId: null,
    jobTitle: null,
    question: null,
  };
}

export function createJobPracticeRequest(profile, job, question) {
  return {
    requestId: newId(),
    source: 'job',
    profileRevision: profileRevisionOf(profile),
    // A market question is not automatically part of any milestone, and guessing which one it
    // belongs to would silently attach progress to the wrong place.
    roadmapId: null,
    milestoneId: null,
    milestoneTitle: '',
    milestoneDescription: '',
    category: null,
    jobId: job?.id ?? null,
    jobTitle: job?.title ?? '',
    question: question ?? '',
  };
}

/**
 * Context sent with every message of a practice session.
 *
 * Prepended to the outgoing message rather than stored in the history, because the history is
 * truncated to the last few turns and the context would be the first thing to fall off. The user
 * never sees this block; the chat renders only what they typed.
 */
export function buildPracticeContextBlock(context) {
  if (!context) return '';
  const lines = ['PRACTICE CONTEXT (added by the app, not written by the student):'];
  lines.push(`- source: ${context.source}`);
  if (context.source === 'roadmap') {
    lines.push(`- roadmap milestone: ${context.milestoneTitle}`);
    if (context.milestoneDescription) {
      lines.push(`- what the milestone asks for: ${context.milestoneDescription}`);
    }
    if (context.category) {
      lines.push(`- category: ${context.category}`);
    }
  } else {
    if (context.jobTitle) lines.push(`- job posting: ${context.jobTitle}`);
    if (context.question) lines.push(`- interview question to ask: ${context.question}`);
  }
  return lines.join('\n');
}

/** Short label shown in the chat header so the user knows which exercise they are in. */
export function practiceLabel(context) {
  if (!context) return null;
  return context.source === 'roadmap' ? context.milestoneTitle : context.jobTitle || 'Interview question';
}
