import React from 'react';
import { Sparkles, User, Briefcase, Award, MessageSquare } from 'lucide-react';

export default function Navbar({ activeTab, setActiveTab, isConnected }) {
  const tabs = [
    { id: 'profile', label: 'Profile & Resume', icon: User },
    { id: 'matching', label: 'Job Matching', icon: Briefcase },
    { id: 'audit', label: 'Resume Audit & Roadmap', icon: Award },
    { id: 'chat', label: 'AI Career Coach', icon: MessageSquare },
  ];

  return (
    <header className="navbar">
      <div className="brand-section">
        <div className="brand-logo-icon">
          <Sparkles size={22} />
        </div>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
            <span className="brand-title">AI Career Coach</span>
            <span className="brand-badge">Global Ready</span>
          </div>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
            AI Career Navigator & Global Opportunity Matcher
          </p>
        </div>
      </div>

      <nav className="nav-tabs">
        {tabs.map((tab) => {
          const Icon = tab.icon;
          return (
            <button
              key={tab.id}
              className={`nav-tab-btn ${activeTab === tab.id ? 'active' : ''}`}
              onClick={() => setActiveTab(tab.id)}
            >
              <Icon size={16} />
              <span>{tab.label}</span>
            </button>
          );
        })}
      </nav>

      <div className="system-status" style={{ borderColor: isConnected ? 'rgba(16, 185, 129, 0.3)' : 'rgba(245, 158, 11, 0.3)' }}>
        <span className="status-dot" style={{ background: isConnected ? '#10b981' : '#f59e0b', boxShadow: isConnected ? '0 0 8px #10b981' : '0 0 8px #f59e0b' }}></span>
        {/* Reports the backend connection only. Whether the AI itself answered is shown per result,
            since the two can differ: the server can be up while Gemini is rate limited. */}
        <span style={{ color: isConnected ? '#10b981' : '#f59e0b' }}>
          {isConnected ? 'Server connected' : 'Offline mode'}
        </span>
      </div>
    </header>
  );
}
