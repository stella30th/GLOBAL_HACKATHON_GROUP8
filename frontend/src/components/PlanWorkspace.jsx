import { useState } from 'react';
import {
  Route, ListChecks, Share2, FileSearch, AlertTriangle, RefreshCw, Loader2, Info,
} from 'lucide-react';
import LearningPathView from './LearningPathView';
import SkillGapView from './SkillGapView';
import KnowledgeGraphView from './KnowledgeGraphView';
import ProfileAuditView from './ProfileAuditView';

const SECTIONS = [
  { id: 'path', label: 'Learning path', icon: Route },
  { id: 'gaps', label: 'Skill gaps', icon: ListChecks },
  { id: 'graph', label: 'Skill graph', icon: Share2 },
  { id: 'audit', label: 'CV review', icon: FileSearch },
];

/**
 * The results column: the whole point of the page, and the largest thing on it.
 *
 * <p>Before anything has been generated this shows the shape of what is coming and what is still
 * needed — an outline, never filled-in example analysis. A page primed with plausible-looking
 * sample findings is indistinguishable from a real result at a glance, and a student who acts on
 * one has been misled by the interface rather than by the model.
 */
export default function PlanWorkspace({
  planState, generating, error, onToggle, busyItemId, onRetry, isConnected,
}) {
  const [section, setSection] = useState('path');
  const plan = planState?.plan;

  if (generating) {
    return <GeneratingState />;
  }

  // A failed regeneration must not take the plan the student is working through off the screen.
  // The server keeps it; so does this, with the failure shown above it rather than in place of it.
  if (error && !plan) {
    return <ErrorState error={error} onRetry={onRetry} isConnected={isConnected} />;
  }

  if (!plan) {
    return <EmptyState planState={planState} />;
  }

  return (
    <div className="plan-workspace">
      {error && (
        <div className="inline-error">
          <AlertTriangle size={15} />
          <div>
            <p>{error.message}</p>
            {error.detail && <p className="inline-error-detail">{error.detail}</p>}
            <p>
              Your existing path below is unchanged — it is the one generated before this attempt.
            </p>
            {error.retryable !== false && (
              <button type="button" className="link-btn" onClick={onRetry} disabled={!isConnected}>
                Try again
              </button>
            )}
          </div>
        </div>
      )}

      <header className="plan-header">
        <div>
          <h2 className="plan-title">
            {plan.goal?.targetRole}
            <span className="plan-seniority">{formatSeniority(plan.goal?.targetSeniority)}</span>
          </h2>
          <p className="plan-subtitle">
            {plan.goal?.durationMonths}-month path · {plan.goal?.hoursPerWeek} hours a week ·{' '}
            {plan.skillGaps?.length || 0} gaps identified
          </p>
        </div>
      </header>

      <nav className="section-tabs" role="tablist">
        {SECTIONS.map((item) => {
          const Icon = item.icon;
          return (
            <button
              key={item.id}
              type="button"
              role="tab"
              aria-selected={section === item.id}
              className={`section-tab ${section === item.id ? 'active' : ''}`}
              onClick={() => setSection(item.id)}
            >
              <Icon size={15} />
              <span>{item.label}</span>
            </button>
          );
        })}
      </nav>

      <div className="section-body">
        {section === 'path' && (
          <LearningPathView
            path={plan.learningPath}
            gaps={plan.skillGaps}
            completedItems={planState.completedItems}
            onToggle={onToggle}
            busyItemId={busyItemId}
          />
        )}
        {section === 'gaps' && (
          <SkillGapView
            gaps={plan.skillGaps}
            evidence={plan.profileEvidence}
            requirements={plan.targetRequirements}
          />
        )}
        {section === 'graph' && <KnowledgeGraphView graph={plan.knowledgeGraph} />}
        {section === 'audit' && <ProfileAuditView audit={plan.audit} />}
      </div>

      <ProvenanceFooter provenance={plan.provenance} />
    </div>
  );
}

/**
 * The footer that answers "where did this come from" for anything above it.
 *
 * <p>Always rendered, never collapsed away entirely. Two different models answering two steps, a
 * missing SFIA file, or a skill the library had no source for all change how much weight the page
 * above deserves, and a reader cannot ask for context they do not know exists.
 */
function ProvenanceFooter({ provenance }) {
  if (!provenance) return null;
  const models = [...new Set(Object.values(provenance.stepModels || {}).filter(Boolean))];

  return (
    <footer className="provenance">
      <div className="provenance-row">
        <Info size={13} />
        <span>
          Generated {formatTimestamp(provenance.generatedAt)} by{' '}
          {models.length ? models.join(' and ') : 'an unrecorded model'}
          {provenance.repairAttempts > 0
            && ` · ${provenance.repairAttempts} answer${provenance.repairAttempts === 1 ? '' : 's'} sent back for correction`}
        </span>
      </div>
      <div className="provenance-row">
        <span>
          Reference data: {provenance.sfiaLoaded
            ? `SFIA 9 (${provenance.sfiaDatasetVersion || 'loaded'})`
            : 'SFIA 9 not loaded'}
          {' · '}
          {provenance.retrievedTaxonomyCodes?.length || 0} taxonomy entries retrieved
          {' · '}
          {provenance.retrievedResourceKeys?.length || 0} documents retrieved
          {provenance.corpusVersion ? ` from catalogue ${provenance.corpusVersion}` : ''}
        </span>
      </div>
      {provenance.limitations?.length > 0 && (
        <details className="provenance-limits">
          <summary>Known limits of this analysis ({provenance.limitations.length})</summary>
          <ul>
            {provenance.limitations.map((limit, index) => <li key={index}>{limit}</li>)}
          </ul>
        </details>
      )}
    </footer>
  );
}

/**
 * Shown while the pipeline runs.
 *
 * <p>The steps are listed because the wait is genuinely long and knowing it is doing four things
 * makes a minute tolerable. There is no progress bar: nothing here reports real progress, and an
 * animated bar that is not measuring anything is a lie about how far along the work is.
 */
function GeneratingState() {
  return (
    <div className="plan-placeholder">
      <Loader2 className="animate-spin placeholder-icon" size={30} />
      <h3>Building your learning path</h3>
      <p className="muted-note">
        This takes a minute or two. Nothing is shown until every step has passed its checks.
      </p>
      <ol className="pipeline-steps">
        <li>Matching your skills to the reference taxonomy</li>
        <li>Working out what the role requires, and where you stand against it</li>
        <li>Mapping how the missing skills depend on each other</li>
        <li>Building the phases and finding sources for them</li>
      </ol>
    </div>
  );
}

function ErrorState({ error, onRetry, isConnected }) {
  return (
    <div className="plan-placeholder">
      <AlertTriangle className="placeholder-icon placeholder-icon-warn" size={30} />
      <h3>No path was built</h3>
      <p>{error.message}</p>
      {error.detail && <p className="inline-error-detail">{error.detail}</p>}
      {/* Said out loud because the alternative - quietly showing a template - is exactly what this
          product must never do, and a student cannot tell the difference from the outside. */}
      <p className="muted-note">
        Nothing was written in its place. There is no stand-in plan behind this page: what you see
        here is generated for you, or it is not there at all.
      </p>
      {error.retryable !== false && (
        <button type="button" className="btn btn-primary" onClick={onRetry} disabled={!isConnected}>
          <RefreshCw size={15} /> Try again
        </button>
      )}
    </div>
  );
}

function EmptyState({ planState }) {
  const missing = planState?.missingInputs || [];

  return (
    <div className="plan-empty">
      <div className="plan-empty-head">
        <Route className="placeholder-icon" size={30} />
        <h3>Your learning path will appear here</h3>
        <p>
          Give us your profile and the role you are aiming at, and this becomes a phased plan:
          which skills to learn, in which order, with the hours it takes and where to learn each one.
        </p>
      </div>

      {planState?.supersededSnapshot && (
        <div className="inline-error">
          <AlertTriangle size={15} />
          <div>
            <p>
              You have generated a path before, but your profile or your goal has changed since.
              The old one is not shown, because it answers a different question.
            </p>
            <p className="inline-error-detail">Build it again to get one for your current inputs.</p>
          </div>
        </div>
      )}

      {missing.length > 0 && (
        <p className="empty-missing">Still needed: {missing.join(', ')}.</p>
      )}

      {/* An outline of the structure, with no content in it. Filling these with example findings
          would be indistinguishable from a real analysis at a glance. */}
      <ol className="skeleton-phases" aria-hidden="true">
        {[1, 2, 3].map((n) => (
          <li key={n} className="skeleton-phase">
            <span className="skeleton-eyebrow">Phase {n}</span>
            <span className="skeleton-bar skeleton-bar-title" />
            <span className="skeleton-bar skeleton-bar-wide" />
            <span className="skeleton-bar skeleton-bar-narrow" />
          </li>
        ))}
      </ol>

      <ul className="empty-promises">
        <li>Skills mapped to a standard framework rather than to loose wording</li>
        <li>Gaps that separate “not in your CV” from “not yet at the level needed”</li>
        <li>Phases ordered by what genuinely has to come first</li>
        <li>Sources that come from a real catalogue, not from the model's memory</li>
      </ul>
    </div>
  );
}

function formatSeniority(value) {
  if (!value) return '';
  return { INTERN: 'Internship', JUNIOR: 'Junior', MID: 'Mid-level', SENIOR: 'Senior' }[value] || value;
}

function formatTimestamp(iso) {
  if (!iso) return 'at an unrecorded time';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}
