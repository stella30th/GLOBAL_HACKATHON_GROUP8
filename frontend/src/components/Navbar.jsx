import { Sparkles, User, Briefcase, Award, MessageSquare, Sun, Moon } from 'lucide-react';

export default function Navbar({ activeTab, setActiveTab, theme, onToggleTheme }) {
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
          <span className="brand-title">AI Career Coach</span>
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

      <button
        type="button"
        className="theme-toggle"
        onClick={onToggleTheme}
        title={theme === 'light' ? 'Switch to dark mode' : 'Switch to light mode'}
        aria-label={theme === 'light' ? 'Switch to dark mode' : 'Switch to light mode'}
      >
        {theme === 'light' ? <Moon size={17} /> : <Sun size={17} />}
      </button>
    </header>
  );
}
