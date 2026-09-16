import React, { useState, useEffect } from 'react';
import { 
  Search, Filter, MapPin, DollarSign, Briefcase, 
  CheckCircle, AlertTriangle, ExternalLink, Sparkles, 
  X, Compass, ShieldCheck, ArrowRight, Plane, RefreshCw, Loader2
} from 'lucide-react';
import { fetchMatches, syncExternalJobs, fetchJobAiDeepDive } from '../api';

export default function JobMatchingView({ profile, showToast }) {
  const [matches, setMatches] = useState([]);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [activeFilter, setActiveFilter] = useState('ALL'); // ALL, VN, OVERSEAS, REMOTE, VISA
  const [selectedMatch, setSelectedMatch] = useState(null);
  const [deepDiveData, setDeepDiveData] = useState({});
  const [loadingDeepDive, setLoadingDeepDive] = useState(false);

  const loadMatches = async () => {
    setLoading(true);
    try {
      const params = {};
      if (keyword.trim()) params.keyword = keyword.trim();
      if (activeFilter === 'VN') params.isOverseas = false;
      if (activeFilter === 'OVERSEAS') params.isOverseas = true;
      if (activeFilter === 'REMOTE') params.workType = 'REMOTE';
      if (activeFilter === 'VISA') params.visaSponsorship = true;

      const data = await fetchMatches(params);
      setMatches(data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadMatches();
  }, [activeFilter]);

  const handleSearchSubmit = (e) => {
    e.preventDefault();
    loadMatches();
  };

  const handleSync = async () => {
    setSyncing(true);
    try {
      const res = await syncExternalJobs();
      if (showToast) {
        showToast(res.message || 'Synced live job opportunities from global APIs! 🌐');
      }
      await loadMatches();
    } catch (err) {
      alert(err.message || 'Error syncing from external APIs');
    } finally {
      setSyncing(false);
    }
  };

  const handleGenerateDeepDive = async (jobId, { force = false } = {}) => {
    if (deepDiveData[jobId] && !force) return;
    setLoadingDeepDive(true);
    try {
      const data = await fetchJobAiDeepDive(jobId);
      setDeepDiveData(prev => ({ ...prev, [jobId]: data }));
      if (showToast) {
        showToast(data.generatedBy === 'gemini'
          ? `✨ ${data.model || 'Gemini'} đã phân tích xong vị trí này!`
          : '⚠️ AI tạm không khả dụng — đang hiển thị phân tích offline.');
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
          <h1>Smart Job Matcher & Global Opportunities</h1>
          <p>
            AI engine matches your profile against live domestic and international opportunities, evaluating technical fit, skill gaps, and visa readiness.
          </p>
        </div>
        <button
          className="btn btn-outline btn-sm"
          onClick={handleSync}
          disabled={syncing || loading}
          title="Fetch live jobs from Arbeitnow (EU/Visa) and Remotive (Global Remote)"
        >
          {syncing ? <Loader2 className="animate-spin" size={15} /> : <RefreshCw size={15} />}
          <span>
            {syncing
              ? 'Đang lấy dữ liệu từ 5 nguồn…'
              : '🔄 Đồng bộ việc làm (Remotive · Jobicy · RemoteOK · The Muse · Arbeitnow)'}
          </span>
        </button>
      </div>

      {/* Filter Bar */}
      <div className="filter-bar">
        <div className="filter-pills">
          <button
            className={`filter-pill ${activeFilter === 'ALL' ? 'active' : ''}`}
            onClick={() => setActiveFilter('ALL')}
          >
            All Jobs ({matches.length})
          </button>
          <button
            className={`filter-pill ${activeFilter === 'VN' ? 'active' : ''}`}
            onClick={() => setActiveFilter('VN')}
          >
            🇻🇳 Domestic (Vietnam)
          </button>
          <button
            className={`filter-pill ${activeFilter === 'OVERSEAS' ? 'active' : ''}`}
            onClick={() => setActiveFilter('OVERSEAS')}
          >
            🌏 Overseas (Europe, Asia, US)
          </button>
          <button
            className={`filter-pill ${activeFilter === 'REMOTE' ? 'active' : ''}`}
            onClick={() => setActiveFilter('REMOTE')}
          >
            🌐 Remote Worldwide
          </button>
          <button
            className={`filter-pill ${activeFilter === 'VISA' ? 'active' : ''}`}
            onClick={() => setActiveFilter('VISA')}
          >
            🛂 Visa Sponsorship
          </button>
        </div>

        <form onSubmit={handleSearchSubmit} style={{ display: 'flex', gap: '0.5rem', flex: '1', maxWidth: '360px' }}>
          <div style={{ position: 'relative', width: '100%' }}>
            <Search
              size={16}
              style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }}
            />
            <input
              type="text"
              className="form-control"
              style={{ paddingLeft: '36px', width: '100%' }}
              placeholder="Search by skill, role, location..."
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </div>
          <button type="submit" className="btn btn-secondary btn-sm">
            Search
          </button>
        </form>
      </div>

      {/* Job Grid */}
      {loading ? (
        <div style={{ textAlign: 'center', padding: '4rem', color: 'var(--text-secondary)' }}>
          <Sparkles className="animate-spin" size={32} style={{ margin: '0 auto 1rem', display: 'block', color: 'var(--accent-primary)' }} />
          AI is evaluating match compatibility and calculating skill gaps...
        </div>
      ) : matches.length === 0 ? (
        <div className="glass-card" style={{ textAlign: 'center', padding: '3.5rem' }}>
          <Compass size={40} color="var(--text-muted)" style={{ margin: '0 auto 1rem', display: 'block' }} />
          <h3 style={{ color: '#fff', marginBottom: '0.5rem' }}>No opportunities found matching this filter</h3>
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
                    <img
                      src={job.companyLogo || 'https://images.unsplash.com/photo-1486406146926-c627a92ad1ab?w=120&auto=format&fit=crop&q=60'}
                      alt={job.company}
                      className="company-avatar"
                    />
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
                      <DollarSign size={14} color="#10b981" />
                      <strong style={{ color: '#10b981' }}>{job.salaryRange}</strong>
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
                        Kỹ năng khớp ({item.matchedSkills.length}) &amp; còn thiếu ({item.missingSkills.length}):
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
                      Tin tuyển dụng này không liệt kê kỹ năng cụ thể — hãy mở mô tả để xem yêu cầu chi tiết.
                    </div>
                  )}

                  {/* AI Quick Insight */}
                  <div
                    style={{
                      background: 'rgba(255, 255, 255, 0.03)',
                      borderLeft: '2px solid var(--accent-primary)',
                      padding: '0.65rem 0.85rem',
                      borderRadius: '6px',
                      fontSize: '0.8rem',
                      color: 'var(--text-secondary)',
                      lineHeight: '1.4',
                      marginBottom: '1.25rem',
                    }}
                  >
                    <span style={{ color: '#818cf8', fontWeight: '600' }}>AI Insight: </span>
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
              <img
                src={selectedMatch.job.companyLogo || 'https://images.unsplash.com/photo-1486406146926-c627a92ad1ab?w=120&auto=format&fit=crop&q=60'}
                alt={selectedMatch.job.company}
                style={{ width: '56px', height: '56px', borderRadius: '12px', objectFit: 'cover' }}
              />
              <div>
                <h2 style={{ fontSize: '1.35rem', color: '#fff', marginBottom: '0.2rem' }}>
                  {selectedMatch.job.title}
                </h2>
                <div style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
                  {selectedMatch.job.company} • {getCountryFlag(selectedMatch.job.country)} {selectedMatch.job.location} • <span style={{ color: '#818cf8' }}>Source: {selectedMatch.job.source || 'Direct'}</span>
                </div>
              </div>
            </div>

            {/* AI Scores Radar / Metric Bars */}
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(4, 1fr)',
                gap: '1rem',
                background: 'rgba(255, 255, 255, 0.03)',
                padding: '1.25rem',
                borderRadius: '14px',
                marginBottom: '1.5rem',
                border: '1px solid var(--border-color)',
              }}
            >
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: '800', color: '#10b981' }}>
                  {selectedMatch.overallScore}%
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                  Overall Match
                </div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: '800', color: '#6366f1' }}>
                  {selectedMatch.skillsScore}%
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                  Skills Overlap
                </div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: '800', color: '#06b6d4' }}>
                  {selectedMatch.experienceScore}%
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                  Experience
                </div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '1.4rem', fontWeight: '800', color: '#f59e0b' }}>
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
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: '#c084fc', fontWeight: '700', fontSize: '0.95rem' }}>
                  <Sparkles size={18} />
                  <span>Phân tích chuyên sâu &amp; chiến lược phỏng vấn</span>
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
                      Phân tích offline
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
                    <span>{loadingDeepDive ? 'Đang phân tích…' : '⚡ Chạy phân tích AI'}</span>
                  </button>
                )}
              </div>

              {loadingDeepDive ? (
                <div style={{ padding: '1.5rem', textAlign: 'center', color: 'var(--text-secondary)' }}>
                  <Sparkles className="animate-spin" size={24} style={{ margin: '0 auto 0.5rem', display: 'block', color: '#c084fc' }} />
                  AI đang đối chiếu hồ sơ của bạn với tin tuyển dụng này, đánh giá khả năng làm việc hợp pháp
                  và soạn câu hỏi phỏng vấn…
                </div>
              ) : deepDiveData[selectedMatch.job.id] ? (
                <div>
                  <p style={{ color: 'var(--text-primary)', fontSize: '0.9rem', lineHeight: '1.6', marginBottom: '1rem' }}>
                    {deepDiveData[selectedMatch.job.id].aiSummary}
                  </p>

                  {/* Deep dive visa */}
                  <div style={{ background: 'rgba(0,0,0,0.25)', borderRadius: '8px', padding: '0.75rem 1rem', marginBottom: '0.85rem' }}>
                    <div style={{ fontSize: '0.8rem', fontWeight: '700', color: '#818cf8', marginBottom: '0.25rem' }}>
                      ✈️ Visa & Immigration Feasibility:
                    </div>
                    <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', lineHeight: '1.5' }}>
                      {deepDiveData[selectedMatch.job.id].visaSuitability}
                    </div>
                  </div>

                  {/* Interview questions */}
                  {deepDiveData[selectedMatch.job.id].interviewQuestions && deepDiveData[selectedMatch.job.id].interviewQuestions.length > 0 && (
                    <div style={{ background: 'rgba(0,0,0,0.25)', borderRadius: '8px', padding: '0.75rem 1rem', marginBottom: '0.85rem' }}>
                      <div style={{ fontSize: '0.8rem', fontWeight: '700', color: '#34d399', marginBottom: '0.35rem' }}>
                        🎯 Predicted Interview Questions & Tech Prep:
                      </div>
                      <ul style={{ paddingLeft: '1.2rem', fontSize: '0.85rem', color: 'var(--text-secondary)', lineHeight: '1.5' }}>
                        {deepDiveData[selectedMatch.job.id].interviewQuestions.map((q, idx) => (
                          <li key={idx} style={{ marginBottom: '0.25rem' }}>{q}</li>
                        ))}
                      </ul>
                      {deepDiveData[selectedMatch.job.id].interviewTips && (
                        <div style={{ marginTop: '0.5rem', fontSize: '0.8rem', color: '#fbbf24' }}>
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
                    <RefreshCw size={12} /> Phân tích lại
                  </button>
                </div>
              ) : (
                <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', margin: 0 }}>
                  Bấm <strong>"⚡ Chạy phân tích AI"</strong> ở trên để AI đối chiếu trực tiếp CV của bạn với tin
                  tuyển dụng này: mức độ phù hợp thật, khoảng trống kỹ năng, câu hỏi phỏng vấn và điều kiện visa.
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
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: '#818cf8', fontWeight: '700', marginBottom: '0.35rem' }}>
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
              <h4 style={{ color: '#fff', fontSize: '0.95rem', marginBottom: '0.75rem' }}>
                Skill Gap & Keyword Alignment
              </h4>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                <div style={{ background: 'rgba(16, 185, 129, 0.05)', padding: '1rem', borderRadius: '10px', border: '1px solid rgba(16, 185, 129, 0.2)' }}>
                  <div style={{ fontSize: '0.8rem', fontWeight: '700', color: '#34d399', marginBottom: '0.5rem' }}>
                    Matched Skills in Your Profile ({selectedMatch.matchedSkills.length})
                  </div>
                  <div className="tag-container">
                    {selectedMatch.matchedSkills.map((s) => (
                      <span key={s} className="skill-tag skill-tag-matched">✓ {s}</span>
                    ))}
                  </div>
                </div>

                <div style={{ background: 'rgba(245, 158, 11, 0.05)', padding: '1rem', borderRadius: '10px', border: '1px solid rgba(245, 158, 11, 0.2)' }}>
                  <div style={{ fontSize: '0.8rem', fontWeight: '700', color: '#fbbf24', marginBottom: '0.5rem' }}>
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
              <h4 style={{ color: '#fff', fontSize: '0.95rem', marginBottom: '0.5rem' }}>
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
              <h4 style={{ color: '#fff', fontSize: '0.95rem', marginBottom: '0.4rem' }}>Role Description & Scope</h4>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem', lineHeight: '1.6', marginBottom: '1rem' }}>
                {selectedMatch.job.description}
              </p>

              <h4 style={{ color: '#fff', fontSize: '0.95rem', marginBottom: '0.4rem' }}>Compensation & Benefits</h4>
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
