import { useState } from 'react';
import { Search, SlidersHorizontal, MapPin, Plus, X, Loader2 } from 'lucide-react';

/**
 * The single filter surface for job matching.
 *
 * Relocation readiness, work arrangement and target markets used to live in a separate card on the
 * Profile tab, away from the results they change. They are folded in here because they are filters
 * in practice: the backend scores every posting against `willingToRelocate` and the candidate's
 * markets, so editing them on another tab changed these results invisibly.
 *
 * Scope pills stay quick and visible; the preferences that are set once sit behind a disclosure so
 * the default view stays simple.
 */
export default function JobFilterPanel({
  resultCount,
  activeFilter,
  onFilterChange,
  keyword,
  onKeywordChange,
  onSearch,
  workType,
  onWorkTypeChange,
  willingToRelocate,
  onRelocateChange,
  targetLocations,
  onAddLocation,
  onRemoveLocation,
  savingPreferences,
}) {
  const [expanded, setExpanded] = useState(false);
  const [newLocation, setNewLocation] = useState('');

  // Anything that differs from the defaults is worth surfacing while the section is collapsed.
  const activePreferences =
    (workType && workType !== 'ANY' ? 1 : 0) +
    (willingToRelocate ? 1 : 0) +
    (targetLocations?.length ? 1 : 0);

  const submitLocation = () => {
    const value = newLocation.trim();
    if (!value) return;
    onAddLocation(value);
    setNewLocation('');
  };

  const scopes = [
    { id: 'ALL', label: `All jobs (${resultCount})` },
    { id: 'VN', label: '🇻🇳 Vietnam' },
    { id: 'OVERSEAS', label: '🌏 Overseas' },
    { id: 'VISA', label: '🛂 Visa sponsorship' },
  ];

  return (
    <section className="filter-panel">
      <div className="filter-panel-row">
        <div className="filter-pills">
          {scopes.map((scope) => (
            <button
              key={scope.id}
              type="button"
              className={`filter-pill ${activeFilter === scope.id ? 'active' : ''}`}
              onClick={() => onFilterChange(scope.id)}
            >
              {scope.label}
            </button>
          ))}
        </div>

        <form
          className="filter-search"
          onSubmit={(e) => {
            e.preventDefault();
            onSearch();
          }}
        >
          <Search size={16} className="filter-search-icon" />
          <input
            type="text"
            className="form-control filter-search-input"
            placeholder="Search by skill, role, location…"
            value={keyword}
            onChange={(e) => onKeywordChange(e.target.value)}
          />
          <button type="submit" className="btn btn-secondary btn-sm">
            Search
          </button>
        </form>
      </div>

      <button
        type="button"
        className="filter-disclosure"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
      >
        <SlidersHorizontal size={15} />
        <span>Work &amp; relocation preferences</span>
        {activePreferences > 0 && <span className="filter-count">{activePreferences}</span>}
        {savingPreferences && <Loader2 className="animate-spin" size={13} />}
        <span className={`filter-chevron ${expanded ? 'open' : ''}`} aria-hidden="true">
          ›
        </span>
      </button>

      {expanded && (
        <div className="filter-advanced">
          <div className="filter-field">
            <label className="form-label" htmlFor="workType">
              Work arrangement
            </label>
            <select
              id="workType"
              className="form-control"
              value={workType || 'ANY'}
              onChange={(e) => onWorkTypeChange(e.target.value)}
            >
              <option value="ANY">Any arrangement</option>
              <option value="REMOTE">Remote</option>
              <option value="HYBRID">Hybrid</option>
              <option value="ONSITE">Onsite</option>
            </select>
          </div>

          <div className="filter-field">
            <label className="form-label" htmlFor="targetMarket">
              Target countries / markets
            </label>
            <div className="filter-inline-input">
              <input
                id="targetMarket"
                type="text"
                className="form-control"
                placeholder="e.g. Singapore, Germany…"
                value={newLocation}
                onChange={(e) => setNewLocation(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    submitLocation();
                  }
                }}
              />
              <button type="button" className="btn btn-secondary btn-sm" onClick={submitLocation}>
                <Plus size={16} />
              </button>
            </div>
            {targetLocations?.length > 0 && (
              <div className="tag-container">
                {targetLocations.map((loc) => (
                  <span key={loc} className="skill-tag">
                    <MapPin size={12} />
                    {loc}
                    <span className="skill-tag-remove" onClick={() => onRemoveLocation(loc)}>
                      <X size={12} />
                    </span>
                  </span>
                ))}
              </div>
            )}
          </div>

          <label className="filter-toggle">
            <input
              type="checkbox"
              checked={!!willingToRelocate}
              onChange={(e) => onRelocateChange(e.target.checked)}
            />
            <span>
              <strong>Open to relocating abroad</strong>
              <em>Raises the score of onsite roles that offer visa sponsorship or relocation support.</em>
            </span>
          </label>
        </div>
      )}
    </section>
  );
}
