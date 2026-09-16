import { useState, useEffect, useRef, useCallback } from 'react';
import {
  DollarSign, Briefcase,
  ExternalLink, Sparkles,
  X, Compass, ShieldCheck, ArrowRight, Plane, RefreshCw, Loader2, Dumbbell
} from 'lucide-react';
import { fetchMatches, fetchJobAiDeepDive, saveProfile } from '../api';
import CompanyLogo from './CompanyLogo';
import JobFilterPanel from './JobFilterPanel';

export default function JobMatchingView({ profile, setProfile, showToast, onPracticeQuestion }) {
  const [matches, setMatches] = useState([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [activeFilter, setActiveFilter] = useState('ALL'); // ALL, VN, OVERSEAS, REMOTE, VISA
  const [selectedMatch, setSelectedMatch] = useState(null);
  const [savingPreferences, setSavingPreferences] = useState(false);
  const saveTimer = useRef(null);
  const [deepDiveData, setDeepDiveData] = useState({});
  const [loadingDeepDive, setLoadingDeepDive] = useState(false);

  const loadMatches = async () => {
    setLoading(true);
    try {
      const params = {};
      if (keyword.trim()) params.keyword = keyword.trim();
      if (activeFilter === 'VN') params.isOverseas = false;
      if (activeFilter === 'OVERSEAS') params.isOverseas = true;
      if (activeFilter === 'VISA') params.visaSponsorship = true;
      // Work arrangement is a real query filter now rather than a profile note.
      if (profile?.targetWorkType && profile.targetWorkType !== 'ANY') {
        params.workType = profile.targetWorkType;
      }

      const data = await fetchMatches(params);
      setMatches(data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const timer = window.setTimeout(loadMatches, 0);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeFilter, profile?.targetWorkType, profile?.willingToRelocate, profile?.targetLocations]);

  /**
   * Preferences live on the profile, so every change is persisted. Writes are debounced: adding
   * three target markets in a row should be one request, not three, and each save triggers a
   * rescore of the whole result set.
   */
  const persistPreferences = useCallback((patch) => {
    const updated = { ...profile, ...patch };
    setProfile(updated);
    if (saveTimer.current) clearTimeout(saveTimer.current);
    setSavingPreferences(true);
    saveTimer.current = setTimeout(async () => {
      try {
        await saveProfile(updated);
      } catch (err) {
        console.error(err);
        if (showToast) showToast('Could not save your preferences.');
      } finally {
        setSavingPreferences(false);
      }
    }, 700);
  }, [profile, setProfile, showToast]);

  useEffect(() => () => {
    if (saveTimer.current) clearTimeout(saveTimer.current);
  }, []);



  const handleGenerateDeepDive = async (jobId, { force = false } = {}) => {
    if (deepDiveData[jobId] && !force) return;
    setLoadingDeepDive(true);
    try {
      const data = await fetchJobAiDeepDive(jobId);
      setDeepDiveData(prev => ({ ...prev, [jobId]: data }));
      if (showToast) {
        showToast(data.generatedBy === 'gemini'
          ? `✨ ${data.model || 'Gemini'} finished analysing this role!`
          : '⚠️ AI is unavailable — showing the offline analysis instead.');
      }
    } catch (err) {
      console.error(err);
      if (showToast) showToast('Failed to fetch AI deep-dive: ' + err.message);
    } finally {
      setLoadingDeepDive(false);
    }
  };

  const getScoreClass = (score) => {
    if (score >= 80) return 'score-high';
    if (score >= 60) return 'score-med';
    return 'score-low';
  };

  const getCountryFlag = (country) => {
    switch (country?.toLowerCase()) {
      case 'vietnam': return '🇻🇳';
      case 'singapore': return '🇸🇬';
      case 'germany':
      case 'germany / europe': return '🇩🇪';
      case 'japan': return '🇯🇵';
      case 'united states': return '🇺🇸';
      case 'australia': return '🇦🇺';
      default: return '🌐';
    }
  };

  return (
    <div>
      <div className="page-header">
        <div className="page-header-text">
          <h1>Market opportunities</h1>
          <p>
            Live postings matched against your profile, to show what employers are actually asking
            for. This is reference material for your roadmap — you do not need a job match here to
            start practising.
          </p>
        </div>
      </div>

      <JobFilterPanel
        resultCount={matches.length}
        activeFilter={activeFilter}
        onFilterChange={setActiveFilter}
        keyword={keyword}
        onKeywordChange={setKeyword}
        onSearch={loadMatches}
        workType={profile?.targetWorkType}
        onWorkTypeChange={(value) => persistPreferences({ targetWorkType: value })}
        willingToRelocate={profile?.willingToRelocate}
        onRelocateChange={(value) => persistPreferences({ willingToRelocate: value })}
        targetLocations={profile?.targetLocations || []}
        onAddLocation={(loc) => {
          const current = profile?.targetLocations || [];
          if (!current.includes(loc)) {
            persistPreferences({ targetLocations: [...current, loc] });
          }
        }}
        onRemoveLocation={(loc) =>
          persistPreferences({
            targetLocations: (profile?.targetLocations || []).filter((item) => item !== loc),
          })
        }
        savingPreferences={savingPreferences}
      />

      {/* Job Grid */}
      {loading ? (
        <div className="loading-panel">
          <Sparkles className="ai-wave" size={32} style={{ margin: '0 auto 1rem', display: 'block', color: 'var(--accent-primary)' }} />
          AI is evaluating match compatibility and calculating skill gaps...
        </div>
      ) : matches.length === 0 ? (
        <div className="glass-card" style={{ textAlign: 'center', padding: '3.5rem' }}>
          <Compass size={40} color="var(--text-muted)" style={{ margin: '0 auto 1rem', display: 'block' }} />
          <h3 style={{ color: 'var(--text-primary)', marginBottom: '0.5rem' }}>No opportunities found matching this filter</h3>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
            Try adjusting your search criteria or switch filter back to "All Jobs".
          </p>
        </div>
      ) : (
        <div className="grid-3">
          {matches.map((item) => {
            const job = item.job;
            return (
              <div key={job.id} className="job-card">
                <div>
                  <div className="job-card-header">
                    <CompanyLogo company={job.company} src={job.companyLogo} size={44} radius={10} />
                    <div className={`match-score-badge ${getScoreClass(item.overallScore)}`}>
                      {item.overallScore}%
                    </div>
                  </div>

                  <h3 className="job-title">{job.title}</h3>
                  <div className="job-company">
                    <span>{job.company}</span>
                    <span>•</span>
                    <span>{getCountryFlag(job.country)} {job.location}</span>
                  </div>

                  <div className="job-meta-row">
                    <span className="job-meta-item">
                      <DollarSign size={14} style={{ color: 'var(--accent-emerald)' }} />
                      <strong style={{ color: 'var(--accent-emerald)' }}>{job.salaryRange}</strong>
                    </span>
                    <span className="job-meta-item">
                      <Briefcase size={14} />
                      {job.workType}
                    </span>
                  </div>

                  {job.visaSponsorship && (
                    <div style={{ marginBottom: '0.85rem' }}>
                      <span className="visa-badge">
                        <ShieldCheck size={12} />
                        Visa Sponsorship & Relocation
                      </span>
                    </div>
                  )}

                  {/* Skills summary. Hidden when the source posting lists no skills at all, since an
                      empty "Matched (0) & Gaps (0)" row reads as a failed match rather than as
                      missing data from the job board. */}
                  {(item.matchedSkills.length > 0 || item.missingSkills.length > 0) ? (
                    <div style={{ marginBottom: '1rem' }}>
                      <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: '0.35rem' }}>
                        Matched ({item.matchedSkills.length}) &amp; missing ({item.missingSkills.length}):
                      </div>
                      <div className="tag-container">
                        {item.matchedSkills.slice(0, 3).map((s) => (
                          <span key={s} className="skill-tag skill-tag-matched">
                            ✓ {s}
                          </span>
                        ))}
                        {item.missingSkills.slice(0, 2).map((s) => (
                          <span key={s} className="skill-tag skill-tag-missing">
                            + {s}
                          </span>
                        ))}
                      </div>
                    </div>
                  ) : (
                    <div style={{ marginBottom: '1rem', fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                      This posting does not list specific skills — open the description for the full requirements.
                    </div>
                  )}

                  {/* AI Quick Insight */}
                  <div
                    style={{
                      background: 'var(--surface-subtle)',
                      borderLeft: '2px solid var(--accent-primary)',
                      padding: '0.65rem 0.85rem',
                      borderRadius: '6px',
                      fontSize: '0.8rem',
                      color: 'var(--text-secondary)',
                      lineHeight: '1.4',
                      marginBottom: '1.25rem',
                    }}
                  >
                    <span style={{ color: 'var(--accent-soft)', fontWeight: '600' }}>AI Insight: </span>
                    {item.aiSummary}
                  </div>
                </div>

                <button
                  className="btn btn-secondary"
                  style={{ width: '100%' }}
                  onClick={() => setSelectedMatch(item)}
                >
                  Deep Analysis & Skill Gap
                  <ArrowRight size={14} />
                </button>
              </div>
            );
          })}
        </div>
      )}

      {/* Detail Modal Drawer */}
      {selectedMatch && (
        <div className="modal-overlay" onClick={() => setSelectedMatch(null)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <button className="modal-close-btn" onClick={() => setSelectedMatch(null)}>
              <X size={18} />
            </button>

            <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', marginBottom: '1.5rem' }}>
              <CompanyLogo company={selectedMatch.job.company} src={selectedMatch.job.companyLogo} size={56} radius={12} />
              <div>
                <h2 style={{ fontSize: '1.35rem', color: 'var(--text-primary)', marginBottom: '0.2rem' }}>
                  {selectedMatch.job.title}
                </h2>
                <div style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
                  {selectedMatch.job.company} • {getCountryFlag(selectedMatch.job.country)} {selectedMatch.job.location} • <span style={{ color: 'var(--accent-soft)' }}>Source: {selectedMatch.job.source || 'Direct'}</span>
                </div>
              </div>
            </div>

            {/* AI Scores Radar / Metric Bars */}
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(4, 1fr)',
                gap: '1rem',
                background: 'var(--surface-subtle)',
                padding: '1.25rem',
                borderRadius: '14px',
                marginBottom: '1.5rem',
                border: '1px solid var(--border-color)',
              }}
            >
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: '800', color: 'var(--accent-emerald)' }}>
                  {selectedMatch.overallScore}%
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                  Overall Match
                </div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: '800', color: 'var(--accent-primary)' }}>
                  {selectedMatch.skillsScore}%
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                  Skills Overlap
                </div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: '800', color: 'var(--accent-secondary)' }}>
                  {selectedMatch.experienceScore}%
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                  Experience
                </div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: '800', color: 'var(--accent-amber)' }}>
                  {selectedMatch.relocationScore}%
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                  Visa & Relocation
                </div>
              </div>
            </div>

            {/* Live AI deep dive. The header and toast below report which engine actually answered
                rather than claiming Gemini unconditionally. */}
            <div
              style={{
                background: 'linear-gradient(135deg, rgba(99, 102, 241, 0.15) 0%, rgba(168, 85, 247, 0.1) 100%)',
                border: '1px solid rgba(168, 85, 247, 0.35)',
                borderRadius: '14px',
                padding: '1.25rem',
                marginBottom: '1.5rem',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '0.75rem', marginBottom: '0.75rem' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: 'var(--accent-violet)', fontWeight: '700', fontSize: '0.95rem' }}>
                  <Sparkles size={18} />
                  <span>Deep dive &amp; interview strategy</span>
                  {deepDiveData[selectedMatch.job.id]?.generatedBy === 'gemini' && (
                    <span className="ai-badge ai-badge-live">
                      {deepDiveData[selectedMatch.job.id].model || 'Gemini'}
                    </span>
                  )}
                  {deepDiveData[selectedMatch.job.id]?.generatedBy === 'offline' && (
                    <span
                      className="ai-badge ai-badge-offline"
                      title={deepDiveData[selectedMatch.job.id].offlineReason || ''}
                    >
                      Offline analysis
                    </span>
                  )}
                </div>
                {!deepDiveData[selectedMatch.job.id] && (
                  <button
                    className="btn btn-primary btn-sm"
                    onClick={() => handleGenerateDeepDive(selectedMatch.job.id)}
                    disabled={loadingDeepDive}
                    style={{ background: 'linear-gradient(135deg, #8b5cf6, #6366f1)' }}
                  >
                    {loadingDeepDive ? <Loader2 className="animate-spin" size={14} /> : <Sparkles size={14} />}
                    <span>{loadingDeepDive ? 'Analysing…' : '⚡ Run AI analysis'}</span>
                  </button>
                )}
              </div>

              {loadingDeepDive ? (
                <div style={{ padding: '1.5rem', textAlign: 'center', color: 'var(--text-secondary)' }}>
                  <Sparkles className="ai-wave" size={24} style={{ margin: '0 auto 0.5rem', display: 'block', color: 'var(--accent-violet)' }} />
                  <span>
                    AI is comparing your profile against this posting, assessing work authorisation
                    and drafting interview questions
                  </span>
                  <span className="wave-dots" style={{ color: 'var(--accent-violet)' }}>
                    <span /><span /><span />
                  </span>
                </div>
              ) : deepDiveData[selectedMatch.job.id] ? (
                <div>
                  <p style={{ color: 'var(--text-primary)', fontSize: '0.9rem', lineHeight: '1.6', marginBottom: '1rem' }}>
                    {deepDiveData[selectedMatch.job.id].aiSummary}
                  </p>

                  {/* Deep dive visa */}
                  <div style={{ background: 'var(--surface-inset)', borderRadius: '8px', padding: '0.75rem 1rem', marginBottom: '0.85rem' }}>
                    <div style={{ fontSize: '0.8rem', fontWeight: '700', color: 'var(--accent-soft)', marginBottom: '0.25rem' }}>
                      ✈️ Visa & Immigration Feasibility:
                    </div>
                    <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', lineHeight: '1.5' }}>
                      {deepDiveData[selectedMatch.job.id].visaSuitability}
                    </div>
                  </div>

                  {/* Interview questions */}
                  {deepDiveData[selectedMatch.job.id].interviewQuestions && deepDiveData[selectedMatch.job.id].interviewQuestions.length > 0 && (
                    <div style={{ background: 'var(--surface-inset)', borderRadius: '8px', padding: '0.75rem 1rem', marginBottom: '0.85rem' }}>
                      <div style={{ fontSize: '0.8rem', fontWeight: '700', color: 'var(--accent-emerald)', marginBottom: '0.35rem' }}>
                        🎯 Predicted Interview Questions & Tech Prep:
                      </div>
                      <ul style={{ paddingLeft: '1.2rem', fontSize: '0.85rem', color: 'var(--text-secondary)', lineHeight: '1.5' }}>
                        {deepDiveData[selectedMatch.job.id].interviewQuestions.map((q, idx) => (
                          <li key={idx} style={{ marginBottom: '0.45rem' }}>
                            {q}
                            {/* Opens a practice session on this exact question. It is an extra
                                source of exercises, not part of the roadmap: answering it here
                                completes no milestone. */}
                            <button
                              type="button"
                              className="btn btn-secondary btn-sm"
                              style={{ marginLeft: '0.5rem', padding: '0.15rem 0.45rem', fontSize: '0.7rem' }}
                              onClick={() => onPracticeQuestion?.(selectedMatch.job, q)}
                            >
                              <Dumbbell size={11} /> Practice this question
                            </button>
                          </li>
                        ))}
                      </ul>
                      {deepDiveData[selectedMatch.job.id].interviewTips && (
                        <div style={{ marginTop: '0.5rem', fontSize: '0.8rem', color: 'var(--accent-amber)' }}>
                          💡 <strong>Pro Tip:</strong> {deepDiveData[selectedMatch.job.id].interviewTips}
                        </div>
                      )}
                    </div>
                  )}

                  <button
                    className="btn btn-outline btn-sm"
                    onClick={() => handleGenerateDeepDive(selectedMatch.job.id, { force: true })}
                    style={{ fontSize: '0.75rem', marginTop: '0.25rem' }}
                  >
                    <RefreshCw size={12} /> Re-run analysis
                  </button>
                </div>
              ) : (
                <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', margin: 0 }}>
                  Click <strong>"⚡ Run AI analysis"</strong> above to compare your CV directly against this
                  posting: real fit, skill gaps, likely interview questions and visa requirements.
                </p>
              )}
            </div>

            {/* Visa & Relocation Assessment */}
            <div
              style={{
                background: 'rgba(99, 102, 241, 0.08)',
                border: '1px solid rgba(99, 102, 241, 0.25)',
                borderRadius: '12px',
                padding: '1.15rem',
                marginBottom: '1.5rem',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: 'var(--accent-soft)', fontWeight: '700', marginBottom: '0.35rem' }}>
                <Plane size={16} />
                <span>Standard Relocation & Visa Snapshot</span>
              </div>
              <p style={{ color: 'var(--text-primary)', fontSize: '0.875rem', lineHeight: '1.5' }}>
                {selectedMatch.visaSuitability}
              </p>
              {selectedMatch.job.languageRequirements && (
                <div style={{ marginTop: '0.5rem', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                  <strong>Language Criteria:</strong> {selectedMatch.job.languageRequirements}
                </div>
              )}
            </div>

            {/* Skill Gap Analysis */}
            <div style={{ marginBottom: '1.5rem' }}>
              <h4 style={{ color: 'var(--text-primary)', fontSize: '0.95rem', marginBottom: '0.75rem' }}>
                Skill Gap & Keyword Alignment
              </h4>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                <div style={{ background: 'rgba(16, 185, 129, 0.05)', padding: '1rem', borderRadius: '10px', border: '1px solid rgba(16, 185, 129, 0.2)' }}>
                  <div style={{ fontSize: '0.8rem', fontWeight: '700', color: 'var(--accent-emerald)', marginBottom: '0.5rem' }}>
                    Matched Skills in Your Profile ({selectedMatch.matchedSkills.length})
                  </div>
                  <div className="tag-container">
                    {selectedMatch.matchedSkills.map((s) => (
                      <span key={s} className="skill-tag skill-tag-matched">✓ {s}</span>
                    ))}
                  </div>
                </div>

                <div style={{ background: 'rgba(245, 158, 11, 0.05)', padding: '1rem', borderRadius: '10px', border: '1px solid rgba(245, 158, 11, 0.2)' }}>
                  <div style={{ fontSize: '0.8rem', fontWeight: '700', color: 'var(--accent-amber)', marginBottom: '0.5rem' }}>
                    Missing / Required Skills ({selectedMatch.missingSkills.length})
                  </div>
                  <div className="tag-container">
                    {selectedMatch.missingSkills.map((s) => (
                      <span key={s} className="skill-tag skill-tag-missing">+ {s}</span>
                    ))}
                  </div>
                </div>
              </div>
            </div>

            {/* AI Action Items */}
            <div style={{ marginBottom: '1.5rem' }}>
              <h4 style={{ color: 'var(--text-primary)', fontSize: '0.95rem', marginBottom: '0.5rem' }}>
                Action Items from AI Coach to Boost Your Offer Probability:
              </h4>
              <ul style={{ paddingLeft: '1.25rem', color: 'var(--text-secondary)', fontSize: '0.875rem', lineHeight: '1.6' }}>
                {selectedMatch.actionItems.map((act, idx) => (
                  <li key={idx} style={{ marginBottom: '0.35rem' }}>{act}</li>
                ))}
              </ul>
            </div>

            {/* Job Description & Requirements */}
            <div style={{ borderTop: '1px solid var(--border-color)', paddingTop: '1.25rem', marginBottom: '1.5rem' }}>
              <h4 style={{ color: 'var(--text-primary)', fontSize: '0.95rem', marginBottom: '0.4rem' }}>Role Description & Scope</h4>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem', lineHeight: '1.6', marginBottom: '1rem' }}>
                {selectedMatch.job.description}
              </p>

              <h4 style={{ color: 'var(--text-primary)', fontSize: '0.95rem', marginBottom: '0.4rem' }}>Compensation & Benefits</h4>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem', lineHeight: '1.6' }}>
                {selectedMatch.job.benefits}
              </p>
            </div>

            {/* Actions */}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '1rem' }}>
              <button className="btn btn-secondary" onClick={() => setSelectedMatch(null)}>
                Close
              </button>
              <a
                href={selectedMatch.job.applyUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="btn btn-primary"
              >
                Apply on {selectedMatch.job.source || 'Company Portal'}
                <ExternalLink size={15} />
              </a>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
