import { useState } from 'react';
import {
  CheckCircle2, Circle, ExternalLink, BookOpen, Hammer, Eye, GraduationCap,
  AlertTriangle, Clock, Target, ChevronDown,
} from 'lucide-react';

const ACTIVITY_ICONS = {
  STUDY: BookOpen,
  PRACTICE: GraduationCap,
  BUILD: Hammer,
  REVIEW: Eye,
};

const FEASIBILITY_TONE = {
  FITS: 'ok',
  TIGHT: 'warn',
  NOT_ACHIEVABLE: 'bad',
};

/**
 * The learning path: the reason the product exists, and the largest thing on the page.
 *
 * <p>Each phase shows what it teaches, why it comes at that point, the work to do, the hours it
 * costs and the sources it draws on. Everything tickable carries a server-issued id, and a tick is
 * sent to the server before the checkbox moves - an optimistic tick that the server then rejects
 * leaves a student believing they have recorded something they have not.
 *
 * <p>The feasibility banner is shown before the phases rather than after them. When a goal does not
 * fit the time available, that is the first thing a student needs to know, not a footnote under a
 * plan they have already started counting on.
 */
export default function LearningPathView({ path, gaps, completedItems, onToggle, busyItemId }) {
  const [openPhases, setOpenPhases] = useState(() => new Set([0]));

  if (!path || !path.phases?.length) {
    return null;
  }

  const completed = new Set(completedItems || []);
  const gapById = new Map((gaps || []).map((gap) => [gap.id, gap]));

  const togglePhaseOpen = (index) => {
    setOpenPhases((current) => {
      const next = new Set(current);
      if (next.has(index)) next.delete(index);
      else next.add(index);
      return next;
    });
  };

  const tone = FEASIBILITY_TONE[path.feasibility?.verdict] || 'warn';

  return (
    <section className="path-view">
      <div className="path-summary">
        <div className="path-summary-stats">
          <div className="path-stat">
            <span className="path-stat-value">{path.durationMonths}</span>
            <span className="path-stat-label">months</span>
          </div>
          <div className="path-stat">
            <span className="path-stat-value">{path.hoursPerWeek}h</span>
            <span className="path-stat-label">per week</span>
          </div>
          <div className="path-stat">
            <span className="path-stat-value">{path.plannedHours}</span>
            <span className="path-stat-label">hours planned</span>
          </div>
          <div className="path-stat">
            <span className="path-stat-value">{path.phases.length}</span>
            <span className="path-stat-label">phases</span>
          </div>
        </div>

        {path.expectedOutcome && (
          <p className="path-outcome">
            <Target size={15} />
            <span>{path.expectedOutcome}</span>
          </p>
        )}
      </div>

      {path.feasibility && (
        <div className={`feasibility-banner feasibility-${tone}`}>
          <AlertTriangle size={16} />
          <div>
            <strong>
              {path.feasibility.verdict === 'FITS' && 'This fits the time you have'}
              {path.feasibility.verdict === 'TIGHT' && 'This is tight for the time you have'}
              {path.feasibility.verdict === 'NOT_ACHIEVABLE'
                && 'The full goal does not fit in this time'}
            </strong>
            <p>{path.feasibility.note}</p>
            {path.feasibility.outOfScope?.length > 0 && (
              <p className="out-of-scope">
                Left out of this plan: {path.feasibility.outOfScope.join(', ')}.
              </p>
            )}
            {path.plannedHours > path.budgetHours && (
              <p className="out-of-scope">
                Planned {path.plannedHours} hours against a budget of about {path.budgetHours}.
              </p>
            )}
          </div>
        </div>
      )}

      <ol className="phase-list">
        {path.phases.map((phase, index) => {
          const isOpen = openPhases.has(index);
          const phaseDone = completed.has(phase.id);
          const criteriaDone = (phase.completionCriteria || [])
            .filter((c) => completed.has(c.id)).length;
          const activitiesDone = (phase.activities || [])
            .filter((a) => completed.has(a.id)).length;

          return (
            <li key={phase.id} className={`phase-card ${phaseDone ? 'phase-done' : ''}`}>
              <div className="phase-header">
                <button
                  type="button"
                  className="phase-check"
                  onClick={() => onToggle(phase.id, !phaseDone)}
                  disabled={busyItemId === phase.id}
                  title={phaseDone ? 'Mark this phase as not finished' : 'I have finished this phase'}
                  aria-pressed={phaseDone}
                >
                  {phaseDone ? <CheckCircle2 size={20} /> : <Circle size={20} />}
                </button>

                <button
                  type="button"
                  className="phase-heading"
                  onClick={() => togglePhaseOpen(index)}
                  aria-expanded={isOpen}
                >
                  <div>
                    <span className="phase-eyebrow">
                      Phase {phase.order} · weeks {phase.startWeek}–{phase.endWeek} ·{' '}
                      {phase.estimatedHours}h
                    </span>
                    <h3 className="phase-title">{phase.title}</h3>
                    <p className="phase-goal">{phase.goal}</p>
                  </div>
                  <ChevronDown
                    size={18}
                    className={`phase-chevron ${isOpen ? 'open' : ''}`}
                  />
                </button>
              </div>

              <div className="phase-progress-line">
                {activitiesDone}/{phase.activities?.length || 0} activities ·{' '}
                {criteriaDone}/{phase.completionCriteria?.length || 0} checks
              </div>

              {isOpen && (
                <div className="phase-body">
                  {phase.skills?.length > 0 && (
                    <div className="phase-block">
                      <h4 className="phase-block-title">Skills in this phase</h4>
                      <div className="tag-container">
                        {phase.skills.map((skill) => (
                          <span key={skill} className="skill-tag">{skill}</span>
                        ))}
                      </div>
                    </div>
                  )}

                  {phase.prerequisiteSkills?.length > 0 && (
                    <p className="phase-prereq">
                      Assumes you already have: {phase.prerequisiteSkills.join(', ')}.
                    </p>
                  )}

                  {phase.orderingRationale && (
                    <p className="phase-why">
                      <strong>Why now:</strong> {phase.orderingRationale}
                    </p>
                  )}

                  {phase.addressesGapIds?.length > 0 && (
                    <div className="phase-block">
                      <h4 className="phase-block-title">Gaps this closes</h4>
                      <ul className="plain-list">
                        {phase.addressesGapIds.map((gapId) => {
                          const gap = gapById.get(gapId);
                          return (
                            <li key={gapId}>
                              {gap ? gap.skillLabel : 'a gap that is no longer listed'}
                              {gap?.severity && <span className="severity-pill">{gap.severity.toLowerCase()}</span>}
                            </li>
                          );
                        })}
                      </ul>
                    </div>
                  )}

                  {phase.activities?.length > 0 && (
                    <div className="phase-block">
                      <h4 className="phase-block-title">What you do</h4>
                      <ul className="activity-list">
                        {phase.activities.map((activity) => {
                          const Icon = ACTIVITY_ICONS[activity.type] || BookOpen;
                          const done = completed.has(activity.id);
                          return (
                            <li key={activity.id} className={done ? 'activity-done' : ''}>
                              <button
                                type="button"
                                className="activity-check"
                                onClick={() => onToggle(activity.id, !done)}
                                disabled={busyItemId === activity.id}
                                aria-pressed={done}
                              >
                                {done ? <CheckCircle2 size={16} /> : <Circle size={16} />}
                              </button>
                              <div className="activity-content">
                                <div className="activity-title-row">
                                  <Icon size={14} />
                                  <strong>{activity.title}</strong>
                                  {activity.estimatedHours != null && (
                                    <span className="hours-pill">
                                      <Clock size={11} /> {activity.estimatedHours}h
                                    </span>
                                  )}
                                </div>
                                <p>{activity.description}</p>
                              </div>
                            </li>
                          );
                        })}
                      </ul>
                    </div>
                  )}

                  {phase.project && (
                    <div className="phase-block project-block">
                      <h4 className="phase-block-title">What you will have at the end</h4>
                      <strong>{phase.project.title}</strong>
                      <p>{phase.project.description}</p>
                      {phase.project.deliverable && (
                        <p className="project-deliverable">
                          Deliverable: {phase.project.deliverable}
                        </p>
                      )}
                    </div>
                  )}

                  {phase.completionCriteria?.length > 0 && (
                    <div className="phase-block">
                      <h4 className="phase-block-title">How you know you are done</h4>
                      <ul className="criteria-list">
                        {phase.completionCriteria.map((criterion) => {
                          const done = completed.has(criterion.id);
                          return (
                            <li key={criterion.id} className={done ? 'activity-done' : ''}>
                              <button
                                type="button"
                                className="activity-check"
                                onClick={() => onToggle(criterion.id, !done)}
                                disabled={busyItemId === criterion.id}
                                aria-pressed={done}
                              >
                                {done ? <CheckCircle2 size={16} /> : <Circle size={16} />}
                              </button>
                              <span>{criterion.text}</span>
                            </li>
                          );
                        })}
                      </ul>
                    </div>
                  )}

                  <div className="phase-block">
                    <h4 className="phase-block-title">Where to learn it</h4>
                    {phase.resources?.length > 0 ? (
                      <ul className="resource-list">
                        {phase.resources.map((resource) => (
                          <li key={`${phase.id}-${resource.resourceKey}`} className="resource-item">
                            <a href={resource.url} target="_blank" rel="noopener noreferrer">
                              {resource.title}
                              <ExternalLink size={12} />
                            </a>
                            <div className="resource-meta">
                              <span>{resource.provider}</span>
                              {resource.type && <span>{resource.type.toLowerCase()}</span>}
                              {resource.level && <span>{resource.level.toLowerCase()}</span>}
                              {/* Cost and duration are shown only when the catalogue records
                                  them. "Free" is the claim a student acts on, and a guess at it
                                  is worse than saying nothing. */}
                              {resource.cost && <span>{resource.cost.toLowerCase()}</span>}
                              {resource.approxHours != null && <span>~{resource.approxHours}h</span>}
                              {resource.urlReachable === false && (
                                <span className="resource-warn">link did not respond when last checked</span>
                              )}
                            </div>
                            {resource.forSkill && (
                              <p className="resource-for">For: {resource.forSkill}</p>
                            )}
                            {resource.whyChosen && (
                              <p className="resource-why">{resource.whyChosen}</p>
                            )}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p className="muted-note">
                        The reference library had nothing for this phase, so no source is listed.
                        Nothing was substituted to fill the space.
                      </p>
                    )}
                  </div>
                </div>
              )}
            </li>
          );
        })}
      </ol>

      <p className="progress-disclaimer">
        Ticks are yours. Nothing here marks itself complete, and finishing a phase is your own
        judgement against the checks above — it is not a qualification.
      </p>
    </section>
  );
}
