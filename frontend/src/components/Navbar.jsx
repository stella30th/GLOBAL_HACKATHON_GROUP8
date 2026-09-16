import { Sparkles, User, Briefcase, Award, MessageSquare } from 'lucide-react';

export default function Navbar({ activeTab, setActiveTab, isConnected }) {
  // Ordered as the product works: describe yourself, see the gaps, practise, then look outward.
  const tabs = [
    { id: 'profile', label: 'My profile', icon: User },
    { id: 'audit', label: 'Skills & roadmap', icon: Award },
    { id: 'chat', label: 'Practice & coach', icon: MessageSquare },
    { id: 'matching', label: 'Market opportunities', icon: Briefcase },
  ];

  return (
    <header className="navbar">
      <div className="brand-section">
        <div className="brand-logo-icon">
          <Sparkles size={22} />
        </div>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
            <span className="brand-title">AI Skills Readiness Coach</span>
            <span className="brand-badge">For students</span>
          </div>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
            Identify your skill gaps. Build your roadmap. Practice for the AI era.
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
