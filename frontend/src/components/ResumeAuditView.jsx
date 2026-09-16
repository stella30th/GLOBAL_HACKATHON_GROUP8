import React, { useState, useEffect } from 'react';
import { 
  Award, TrendingUp, CheckCircle2, AlertTriangle, 
  ArrowRight, Sparkles, Clock, Compass, Target, 
  BookOpen, Shield, RefreshCw
} from 'lucide-react';
import { fetchProfileAudit } from '../api';

export default function ResumeAuditView({ profile }) {
  const [audit, setAudit] = useState(null);
  const [loading, setLoading] = useState(true);

  const loadAudit = async () => {
    setLoading(true);
    try {
      const data = await fetchProfileAudit();
      setAudit(data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadAudit();
  }, [profile?.fullName, profile?.currentTitle, profile?.updatedAt]);

  if (loading) {
    return (
      <div className="loading-panel">
        <Sparkles className="ai-wave" size={32} style={{ margin: '0 auto 1rem', display: 'block', color: 'var(--accent-primary)' }} />
        AI is reviewing <strong>{profile?.fullName || 'your profile'}</strong>
        {profile?.industry ? <> against hiring standards in <strong>{profile.industry}</strong></> : null}…
      </div>
    );
  }

  if (!audit) {
    return (
      <div className="glass-card" style={{ textAlign: 'center', padding: '3rem' }}>
        <p>Could not load resume audit data.</p>
        <button className="btn btn-primary" onClick={loadAudit} style={{ marginTop: '1rem' }}>
          Retry
        </button>
      </div>
    );
  }

  const roadmap = audit.careerRoadmap;

  return (
    <div>
      <div className="page-header">
        <div className="page-header-text">
          <h1>Resume Audit & Career Progression Roadmap</h1>
          <p>
            Candidate: <strong style={{ color: '#fff' }}>{profile?.fullName || 'Current profile'}</strong>
            {' '}({profile?.currentTitle || 'role not set'}{profile?.industry ? ` — ${profile.industry}` : ''})
            {' '}• Scored against international ATS standards for your own field.
          </p>
        </div>
        <button className="btn btn-primary btn-sm" onClick={loadAudit} disabled={loading}>
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} /> {loading ? 'Analyzing...' : 'Re-Scan Current Profile'}
        </button>
      </div>

      {/* Hero Health Score Card */}
      <div className="score-hero-card">
        <div style={{ maxWidth: '700px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem', marginBottom: '0.5rem', flexWrap: 'wrap' }}>
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', color: '#818cf8', fontSize: '0.85rem', fontWeight: '700', textTransform: 'uppercase' }}>
              <Award size={16} />
              <span>Resume Health Index (ATS Benchmark)</span>
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
          <h2 style={{ fontSize: '1.8rem', color: '#ffffff', marginBottom: '0.6rem' }}>
            {audit.verdict}
          </h2>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.925rem', lineHeight: '1.6' }}>
            {audit.summary}
          </p>
        </div>

        <div className="score-circle">
          <span className="score-num">{audit.healthScore}</span>
          <span className="score-label">Score / 100</span>
        </div>
      </div>

      {/* Strengths & Weaknesses SWOT */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem', marginBottom: '2rem' }}>
        <div className="glass-card">
          <div className="card-title-row">
            <div className="card-title" style={{ color: '#34d399' }}>
              <CheckCircle2 size={20} color="#34d399" />
              <span>Key Profile Strengths</span>
            </div>
          </div>
          <ul style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', paddingLeft: '1.2rem', color: 'var(--text-primary)', fontSize: '0.9rem' }}>
            {audit.strengths.map((str, idx) => (
              <li key={idx} style={{ lineHeight: '1.5' }}>{str}</li>
            ))}
          </ul>
        </div>

        <div className="glass-card">
          <div className="card-title-row">
            <div className="card-title" style={{ color: '#fbbf24' }}>
              <AlertTriangle size={20} color="#fbbf24" />
              <span>Areas to Improve & Red Flags</span>
            </div>
          </div>
          <ul style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', paddingLeft: '1.2rem', color: 'var(--text-primary)', fontSize: '0.9rem' }}>
            {audit.weaknesses.map((weak, idx) => (
              <li key={idx} style={{ lineHeight: '1.5' }}>{weak}</li>
            ))}
          </ul>
        </div>
      </div>

      {/* ATS Keyword Analysis */}
      <div className="glass-card" style={{ marginBottom: '2rem' }}>
        <div className="card-title-row">
          <div className="card-title">
            <Target size={20} color="#06b6d4" />
            <span>Applicant Tracking System (ATS) Keyword Analysis</span>
          </div>
        </div>
        <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem', marginBottom: '1rem' }}>
          Global tech recruiters use automated ATS scanners to rank candidates. Including target keywords and system terminology directly impacts interview invitation rates by up to 80%.
        </p>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem' }}>
          <div>
            <div style={{ fontSize: '0.8rem', fontWeight: '700', color: '#34d399', marginBottom: '0.5rem' }}>
              Keywords Found in Your Profile ({audit.atsKeywordsPresent.length}):
            </div>
            <div className="tag-container">
              {audit.atsKeywordsPresent.map((kw) => (
                <span key={kw} className="skill-tag skill-tag-matched">✓ {kw}</span>
              ))}
            </div>
          </div>

          <div>
            <div style={{ fontSize: '0.8rem', fontWeight: '700', color: '#f59e0b', marginBottom: '0.5rem' }}>
              High-Impact Missing Keywords Recommended ({audit.atsKeywordsMissing.length}):
            </div>
            <div className="tag-container">
              {audit.atsKeywordsMissing.map((kw) => (
                <span key={kw} className="skill-tag skill-tag-missing">+ {kw}</span>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* STAR Formula Bullet Improvements */}
      <div className="glass-card" style={{ marginBottom: '2rem' }}>
        <div className="card-title-row">
          <div className="card-title">
            <Sparkles size={20} color="#a855f7" />
            <span>Experience Bullet Point Rewrites (Google & Amazon STAR Formula)</span>
          </div>
        </div>
        <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem', marginBottom: '1.25rem' }}>
          Transform passive duties into impactful, quantifiable achievements with measurable business metrics:
        </p>

        {audit.bulletImprovements.map((item, idx) => (
          <div key={idx} className="star-card">
            <div style={{ fontSize: '0.75rem', fontWeight: '700', color: '#ef4444', textTransform: 'uppercase', marginBottom: '0.25rem' }}>
              Original (Passive / Generic):
            </div>
            <div className="star-before">
              "{item.original}"
            </div>

            <div style={{ fontSize: '0.75rem', fontWeight: '700', color: '#10b981', textTransform: 'uppercase', marginBottom: '0.25rem' }}>
              AI Improved (STAR Formula + Measurable Impact):
            </div>
            <div className="star-after">
              "{item.improved}"
            </div>

            <div className="star-note">
              💡 <strong>Coach Rationale:</strong> {item.rationale}
            </div>
          </div>
        ))}
      </div>

      {/* 3-6-12 Months Career Roadmap */}
      {roadmap && (
        <div className="glass-card">
          <div className="card-title-row">
            <div className="card-title">
              <Compass size={20} color="#6366f1" />
              <span>Personalized 12-Month Career Progression Roadmap</span>
            </div>
            <span style={{ fontSize: '0.85rem', color: '#818cf8', fontWeight: '600' }}>
              Target: {roadmap.targetGoal}
            </span>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '1.5rem', marginTop: '1.5rem' }}>
            {/* 3 Months */}
            <div style={{ background: 'rgba(255, 255, 255, 0.02)', padding: '1.25rem', borderRadius: '14px', border: '1px solid var(--border-color)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '1rem' }}>
                <span style={{ padding: '0.25rem 0.6rem', borderRadius: '6px', background: 'rgba(99, 102, 241, 0.2)', color: '#818cf8', fontWeight: '700', fontSize: '0.8rem' }}>
                  Phase 1
                </span>
                <strong style={{ color: '#fff', fontSize: '0.95rem' }}>Months 1-3: Core Gaps & ATS</strong>
              </div>
              <div>
                {roadmap.months3.map((m, idx) => (
                  <div key={idx} className="timeline-item">
                    <div className="timeline-dot"></div>
                    <div style={{ fontWeight: '600', color: '#fff', fontSize: '0.875rem', marginBottom: '0.25rem' }}>
                      {m.title}
                    </div>
                    <p style={{ color: 'var(--text-secondary)', fontSize: '0.785rem', lineHeight: '1.45', marginBottom: '0.35rem' }}>
                      {m.description}
                    </p>
                    <span style={{ fontSize: '0.7rem', color: 'var(--accent-secondary)' }}>
                      ⏱ Est. Effort: {m.estimatedHours}
                    </span>
                  </div>
                ))}
              </div>
            </div>

            {/* 6 Months */}
            <div style={{ background: 'rgba(255, 255, 255, 0.02)', padding: '1.25rem', borderRadius: '14px', border: '1px solid var(--border-color)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '1rem' }}>
                <span style={{ padding: '0.25rem 0.6rem', borderRadius: '6px', background: 'rgba(6, 182, 212, 0.2)', color: '#06b6d4', fontWeight: '700', fontSize: '0.8rem' }}>
                  Phase 2
                </span>
                <strong style={{ color: '#fff', fontSize: '0.95rem' }}>Months 4-6: Certs & Open-Source</strong>
              </div>
              <div>
                {roadmap.months6.map((m, idx) => (
                  <div key={idx} className="timeline-item">
                    <div className="timeline-dot" style={{ background: '#06b6d4', boxShadow: '0 0 10px #06b6d4' }}></div>
                    <div style={{ fontWeight: '600', color: '#fff', fontSize: '0.875rem', marginBottom: '0.25rem' }}>
                      {m.title}
                    </div>
                    <p style={{ color: 'var(--text-secondary)', fontSize: '0.785rem', lineHeight: '1.45', marginBottom: '0.35rem' }}>
                      {m.description}
                    </p>
                    <span style={{ fontSize: '0.7rem', color: 'var(--accent-secondary)' }}>
                      ⏱ Est. Effort: {m.estimatedHours}
                    </span>
                  </div>
                ))}
              </div>
            </div>

            {/* 12 Months */}
            <div style={{ background: 'rgba(255, 255, 255, 0.02)', padding: '1.25rem', borderRadius: '14px', border: '1px solid var(--border-color)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '1rem' }}>
                <span style={{ padding: '0.25rem 0.6rem', borderRadius: '6px', background: 'rgba(16, 185, 129, 0.2)', color: '#34d399', fontWeight: '700', fontSize: '0.8rem' }}>
                  Phase 3
                </span>
                <strong style={{ color: '#fff', fontSize: '0.95rem' }}>Months 7-12: Global Job Search</strong>
              </div>
              <div>
                {roadmap.months12.map((m, idx) => (
                  <div key={idx} className="timeline-item">
                    <div className="timeline-dot" style={{ background: '#10b981', boxShadow: '0 0 10px #10b981' }}></div>
                    <div style={{ fontWeight: '600', color: '#fff', fontSize: '0.875rem', marginBottom: '0.25rem' }}>
                      {m.title}
                    </div>
                    <p style={{ color: 'var(--text-secondary)', fontSize: '0.785rem', lineHeight: '1.45', marginBottom: '0.35rem' }}>
                      {m.description}
                    </p>
                    <span style={{ fontSize: '0.7rem', color: 'var(--accent-secondary)' }}>
                      ⏱ Est. Effort: {m.estimatedHours}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
