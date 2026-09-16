import { Compass, MessageCircleQuestion, Database, Sun, Moon } from 'lucide-react';

/**
 * The page header.
 *
 * There are no navigation tabs any more. The product does one thing, and a row of equal-weight
 * tabs told a first-time visitor the opposite - that building a learning path was one of four
 * things on offer, and not obviously the one to start with. What is left is the name, the
 * promise, and two secondary tools that open panels over the page rather than replacing it.
 */
export default function Header({ theme, onToggleTheme, onOpenChat, onOpenDataPanel, chatAvailable }) {
  return (
    <header className="navbar">
      <div className="brand-section">
        <div className="brand-logo-icon">
          <Compass size={22} />
        </div>
        <div>
          <div className="brand-title-row">
            <span className="brand-title">Skill Path</span>
            <span className="brand-badge">Learning roadmap</span>
          </div>
          <p className="brand-tagline">
            Build a learning path that fits your profile, your target role and your time.
          </p>
        </div>
      </div>

      <div className="header-actions">
        <button
          type="button"
          className="btn btn-outline btn-sm"
          onClick={onOpenDataPanel}
          title="See which reference data this analysis was built on"
        >
          <Database size={15} />
          <span className="header-action-label">Sources &amp; data</span>
        </button>

        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={onOpenChat}
          disabled={!chatAvailable}
          title={chatAvailable
            ? 'Ask a question about your plan'
            : 'Available once the server is reachable'}
        >
          <MessageCircleQuestion size={15} />
          <span className="header-action-label">Ask about your path</span>
        </button>

        <button
          type="button"
          className="theme-toggle"
          onClick={onToggleTheme}
          title={theme === 'light' ? 'Switch to dark mode' : 'Switch to light mode'}
          aria-label={theme === 'light' ? 'Switch to dark mode' : 'Switch to light mode'}
        >
          {theme === 'light' ? <Moon size={17} /> : <Sun size={17} />}
        </button>
      </div>
    </header>
  );
}
