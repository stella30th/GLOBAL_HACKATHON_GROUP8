import { useState, useEffect, useCallback } from 'react';
import {
  Award, CheckCircle2, AlertTriangle, Sparkles, Compass, Target,
  RefreshCw, Dumbbell, BrainCircuit, Loader2,
} from 'lucide-react';
import { fetchProfileAudit } from '../api';

/**
 * Category badges. AI_FLUENCY is the one this product exists for, so it gets its own colour;
 * everything else falls back to a neutral style rather than rendering blank, because the category
 * comes from a language model and will eventually contain something this list does not know.
 */
const CATEGORY_META = {
  AI_FLUENCY: { label: 'AI fluency', color: '#a855f7', background: 'rgba(168, 85, 247, 0.16)' },
  SKILL: { label: 'Skill', color: '#818cf8', background: 'rgba(99, 102, 241, 0.16)' },
  PROJECT: { label: 'Project', color: '#06b6d4', background: 'rgba(6, 182, 212, 0.16)' },
  CERTIFICATION: { label: 'Certification', color: '#f59e0b', background: 'rgba(245, 158, 11, 0.16)' },
  LANGUAGE: { label: 'Language', color: '#34d399', background: 'rgba(16, 185, 129, 0.16)' },
  NETWORKING: { label: 'Networking', color: '#f472b6', background: 'rgba(244, 114, 182, 0.16)' },
  APPLICATION: { label: 'Applications', color: '#38bdf8', background: 'rgba(56, 189, 248, 0.16)' },
  INTERVIEW: { label: 'Interview', color: '#fbbf24', background: 'rgba(251, 191, 36, 0.16)' },
};

const FALLBACK_CATEGORY = { label: 'Milestone', color: 'var(--text-secondary)', background: 'rgba(255,255,255,0.08)' };

function categoryMeta(category) {
  if (!category) return FALLBACK_CATEGORY;
  return CATEGORY_META[String(category).toUpperCase().replace(/[\s-]/g, '_')] || FALLBACK_CATEGORY;
}

const STAGES = [
  { key: 'months3', phase: 'Phase 1', heading: 'Months 1-3: Foundations & first evidence', dot: '#818cf8' },
  { key: 'months6', phase: 'Phase 2', heading: 'Months 4-6: Build something worth showing', dot: '#06b6d4' },
  { key: 'months12', phase: 'Phase 3', heading: 'Months 7-12: Apply, interview, connect', dot: '#10b981' },
];

export default function ResumeAuditView({ profile, onPracticeMilestone, onToggleMilestone }) {
  const [audit, setAudit] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);
  // Milestone ids with a progress request in flight; their checkbox is disabled until it settles.
  const [savingIds, setSavingIds] = useState([]);

  const loadAudit = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const data = await fetchProfileAudit();
      setAudit(data);
    } catch (err) {
      console.error(err);
      setLoadError(err.message || 'Could not load your analysis.');
    } finally {
      setLoading(false);
    }
  }, []);

  /**
   * Reload only when the profile content actually changed. Ticking a milestone updates the profile
   * object too, and refetching on that would have thrown away the roadmap mid-tick.
   */
  useEffect(() => {
    loadAudit();
  }, [loadAudit, profile?.id, profile?.updatedAt]);

  const roadmap = audit?.careerRoadmap;
  const roadmapId = roadmap?.roadmapId || null;
  const completed = profile?.completedMilestones || [];
  const allMilestones = STAGES.flatMap((stage) => roadmap?.[stage.key] || []);
  const completedCount = allMilestones.filter((m) => completed.includes(m.id)).length;

  const handleToggle = async (milestone) => {
    if (!roadmapId || !milestone?.id || savingIds.includes(milestone.id)) return;
    const next = !completed.includes(milestone.id);
    setSavingIds((prev) => [...prev, milestone.id]);
    try {
      const result = await onToggleMilestone?.(milestone.id, roadmapId, next);
      if (result?.reload) {
        await loadAudit();
      }
    } finally {
      setSavingIds((prev) => prev.filter((id) => id !== milestone.id));
    }
  };

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '4rem', color: 'var(--text-secondary)' }}>
        <Sparkles className="animate-spin" size={32} style={{ margin: '0 auto 1rem', display: 'block', color: 'var(--accent-primary)' }} />
        Reading <strong>{profile?.fullName || 'your profile'}</strong>
        {profile?.industry ? <> against what employers look for in <strong>{profile.industry}</strong></> : null}…
      </div>
    );
  }

  if (!audit) {
    return (
      <div className="glass-card" style={{ textAlign: 'center', padding: '3rem' }}>
        <p>{loadError || 'Could not load your analysis.'}</p>
        <button className="btn btn-primary" onClick={loadAudit} style={{ marginTop: '1rem' }}>
          Try again
        </button>
      </div>
    );
  }

  const renderMilestone = (milestone, dotColour) => {
    const meta = categoryMeta(milestone.category);
    const isDone = milestone.id ? completed.includes(milestone.id) : false;
    const isSaving = milestone.id ? savingIds.includes(milestone.id) : false;

    return (
      <div key={milestone.id || milestone.title} className="timeline-item">
        <div className="timeline-dot" style={{ background: dotColour, boxShadow: `0 0 10px ${dotColour}` }}></div>

        <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem', marginBottom: '0.3rem' }}>
          <input
            type="checkbox"
            checked={isDone}
            disabled={isSaving || !milestone.id || !roadmapId}
            onChange={() => handleToggle(milestone)}
            title="Mark as done (self-reported)"
            style={{ marginTop: '0.15rem', width: '15px', height: '15px', accentColor: 'var(--accent-primary)', flexShrink: 0 }}
          />
          <div style={{
            fontWeight: '600',
            color: isDone ? 'var(--text-secondary)' : '#fff',
            fontSize: '0.875rem',
            textDecoration: isDone ? 'line-through' : 'none',
          }}>
            {milestone.title}
          </div>
          {isSaving && <Loader2 className="animate-spin" size={13} style={{ marginTop: '0.15rem' }} />}
        </div>

        <p style={{ color: 'var(--text-secondary)', fontSize: '0.785rem', lineHeight: '1.45', marginBottom: '0.45rem' }}>
          {milestone.description}
        </p>

        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
          <span style={{
            fontSize: '0.68rem', fontWeight: 700, padding: '0.15rem 0.45rem', borderRadius: '5px',
            color: meta.color, background: meta.background,
          }}>
            {meta.label}
          </span>
          <span style={{ fontSize: '0.7rem', color: 'var(--accent-secondary)' }}>
            ⏱ {milestone.estimatedHours || 'effort not estimated'}
          </span>
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            style={{ padding: '0.2rem 0.5rem', fontSize: '0.72rem' }}
            onClick={() => onPracticeMilestone?.(milestone, roadmapId)}
          >
            <Dumbbell size={12} /> Practice this skill
          </button>
        </div>
      </div>
    );
  };

  return (
    <div>
      <div className="page-header">
        <div className="page-header-text">
          <h1>Skills & roadmap</h1>
          <p>
            Reading the profile of <strong style={{ color: '#fff' }}>{profile?.fullName || 'your current profile'}</strong>
            {' '}({profile?.currentTitle || 'specialisation not set'}
            {profile?.yearOfStudy ? `, ${profile.yearOfStudy}` : ''}
            {profile?.industry ? ` — ${profile.industry}` : ''}).
            {' '}Strengths, gaps and a 12-month plan you can practise against.
          </p>
        </div>
        <button className="btn btn-primary btn-sm" onClick={loadAudit} disabled={loading}>
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} /> {loading ? 'Loading…' : 'Reload analysis'}
        </button>
      </div>

      {/* Hero score card */}
      <div className="score-hero-card">
        <div style={{ maxWidth: '700px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem', marginBottom: '0.5rem', flexWrap: 'wrap' }}>
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', color: 'var(--accent-soft)', fontSize: '0.85rem', fontWeight: '700', textTransform: 'uppercase' }}>
              <Award size={16} />
              <span>CV / profile quality score (ATS screening)</span>
            </div>
            {audit.generatedBy === 'offline' ? (
              <span className="ai-badge ai-badge-offline" title={audit.offlineReason || ''}>
                Offline analysis (AI unavailable)
              </span>
            ) : (
              <span className="ai-badge ai-badge-live">
                <Sparkles size={12} /> {audit.model || 'Gemini'}
              </span>
            )}
          </div>
          <h2 style={{ fontSize: '1.8rem', color: 'var(--text-primary)', marginBottom: '0.6rem' }}>
            {audit.verdict}
          </h2>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.925rem', lineHeight: '1.6' }}>
            {audit.summary}
          </p>
          {/* Said plainly, because the number is easy to mistake for a verdict on the person. */}
          <p style={{ color: 'var(--text-muted)', fontSize: '0.78rem', marginTop: '0.75rem', lineHeight: '1.5' }}>
            This scores your CV and profile as a document that has to survive automated screening.
            It is not a measure of your ability and not an AI-readiness score. Where the profile is
            silent, that is missing evidence — not a missing skill.
          </p>
        </div>

        <div className="score-circle">
          <span className="score-num">{audit.healthScore}</span>
          <span className="score-label">Score / 100</span>
        </div>
      </div>

      {/* Strengths & gaps */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem', marginBottom: '2rem' }}>
        <div className="glass-card">
          <div className="card-title-row">
            <div className="card-title" style={{ color: '#34d399' }}>
              <CheckCircle2 size={20} color="#34d399" />
              <span>What your profile already evidences</span>
            </div>
          </div>
          <ul style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', paddingLeft: '1.2rem', color: 'var(--text-primary)', fontSize: '0.9rem' }}>
            {(audit.strengths || []).map((str, idx) => (
              <li key={idx} style={{ lineHeight: '1.5' }}>{str}</li>
            ))}
          </ul>
        </div>

        <div className="glass-card">
          <div className="card-title-row">
            <div className="card-title" style={{ color: '#fbbf24' }}>
              <AlertTriangle size={20} color="#fbbf24" />
              <span>Gaps and missing evidence</span>
            </div>
          </div>
          <ul style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', paddingLeft: '1.2rem', color: 'var(--text-primary)', fontSize: '0.9rem' }}>
            {(audit.weaknesses || []).map((weak, idx) => (
              <li key={idx} style={{ lineHeight: '1.5' }}>{weak}</li>
            ))}
          </ul>
        </div>
      </div>

      {/* Keyword analysis */}
      <div className="glass-card" style={{ marginBottom: '2rem' }}>
        <div className="card-title-row">
          <div className="card-title">
            <Target size={20} color="#06b6d4" />
            <span>Keyword coverage for your field (ATS screening)</span>
          </div>
        </div>
        <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem', marginBottom: '1rem' }}>
          Most employers filter applications with an automated tracking system before a person reads
          them. These are the terms your field's postings use — add the ones you can back up truthfully.
        </p>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem' }}>
          <div>
            <div style={{ fontSize: '0.8rem', fontWeight: '700', color: '#34d399', marginBottom: '0.5rem' }}>
              Already in your profile ({(audit.atsKeywordsPresent || []).length}):
            </div>
            <div className="tag-container">
              {(audit.atsKeywordsPresent || []).map((kw) => (
                <span key={kw} className="skill-tag skill-tag-matched">✓ {kw}</span>
              ))}
            </div>
          </div>

          <div>
            <div style={{ fontSize: '0.8rem', fontWeight: '700', color: '#f59e0b', marginBottom: '0.5rem' }}>
              Worth adding if you can evidence them ({(audit.atsKeywordsMissing || []).length}):
            </div>
            <div className="tag-container">
              {(audit.atsKeywordsMissing || []).map((kw) => (
                <span key={kw} className="skill-tag skill-tag-missing">+ {kw}</span>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Bullet rewrites */}
      {(audit.bulletImprovements || []).length > 0 && (
        <div className="glass-card" style={{ marginBottom: '2rem' }}>
          <div className="card-title-row">
            <div className="card-title">
              <Sparkles size={20} color="#a855f7" />
              <span>Rewriting your experience with the STAR formula</span>
            </div>
          </div>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem', marginBottom: '1.25rem' }}>
            Turn duties and coursework into results a reader can judge. Fill the placeholders with
            your own real numbers — never with invented ones.
          </p>

          {(audit.bulletImprovements || []).map((item, idx) => (
            <div key={idx} className="star-card">
              <div style={{ fontSize: '0.75rem', fontWeight: '700', color: '#ef4444', textTransform: 'uppercase', marginBottom: '0.25rem' }}>
                Before (passive / generic):
              </div>
              <div className="star-before">
                "{item.original}"
              </div>

              <div style={{ fontSize: '0.75rem', fontWeight: '700', color: '#10b981', textTransform: 'uppercase', marginBottom: '0.25rem' }}>
                After (STAR + measurable impact):
              </div>
              <div className="star-after">
                "{item.improved}"
              </div>

              <div className="star-note">
                💡 <strong>Why it is stronger:</strong> {item.rationale}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* 3 / 6 / 12 month roadmap */}
      {roadmap && (
        <div className="glass-card">
          <div className="card-title-row">
            <div className="card-title">
              <Compass size={20} color="#6366f1" />
              <span>Your 12-month learning roadmap</span>
            </div>
            <span style={{ fontSize: '0.85rem', color: '#818cf8', fontWeight: '600' }}>
              Goal: {roadmap.targetGoal}
            </span>
          </div>

          <div style={{
            display: 'flex', alignItems: 'center', gap: '0.6rem', flexWrap: 'wrap',
            marginTop: '0.75rem', padding: '0.6rem 0.85rem', borderRadius: '10px',
            background: 'rgba(99, 102, 241, 0.08)', border: '1px solid var(--border-color)',
          }}>
            <BrainCircuit size={16} color="#818cf8" />
            <strong style={{ color: '#fff', fontSize: '0.85rem' }}>
              Self-reported progress: {completedCount} of {allMilestones.length} done
            </strong>
            <span style={{ color: 'var(--text-secondary)', fontSize: '0.78rem' }}>
              You decide when a milestone is finished — tick it, or untick it if you want to revisit.
              Nothing here is scored or certified by the AI.
            </span>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1.5rem', marginTop: '1.5rem' }}>
            {STAGES.map((stage) => (
              <div
                key={stage.key}
                style={{ background: 'rgba(255, 255, 255, 0.02)', padding: '1.25rem', borderRadius: '14px', border: '1px solid var(--border-color)' }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '1rem', flexWrap: 'wrap' }}>
                  <span style={{ padding: '0.25rem 0.6rem', borderRadius: '6px', background: `${stage.dot}33`, color: stage.dot, fontWeight: '700', fontSize: '0.8rem' }}>
                    {stage.phase}
                  </span>
                  <strong style={{ color: '#fff', fontSize: '0.9rem' }}>{stage.heading}</strong>
                </div>
                <div>
                  {(roadmap[stage.key] || []).map((milestone) => renderMilestone(milestone, stage.dot))}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
