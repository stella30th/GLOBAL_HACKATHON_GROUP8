import { HelpCircle, CheckCircle2, MinusCircle } from 'lucide-react';

const EVIDENCE_LABEL = {
  HAS_EVIDENCE: 'Evidenced in your profile',
  LIMITED_EVIDENCE: 'Mentioned, but thinly evidenced',
  NO_DATA: 'Your profile does not mention it',
};

const EVIDENCE_ICON = {
  HAS_EVIDENCE: CheckCircle2,
  LIMITED_EVIDENCE: MinusCircle,
  NO_DATA: HelpCircle,
};

const SEVERITY_ORDER = { CRITICAL: 0, SIGNIFICANT: 1, MINOR: 2 };

/**
 * The gap analysis, and the evidence it rests on.
 *
 * <p>Evidence status is shown on every row and never collapsed into "you don't have this". A CV
 * that is silent about testing does not say the student cannot test; it says the CV is silent, and
 * the two lead to different work - one is study, the other is writing down what you already did.
 * The row says which, and what would settle it.
 */
export default function SkillGapView({ gaps, evidence, requirements }) {
  if (!gaps?.length) return null;

  const sorted = [...gaps].sort((a, b) => {
    const bySeverity = (SEVERITY_ORDER[a.severity] ?? 3) - (SEVERITY_ORDER[b.severity] ?? 3);
    return bySeverity !== 0 ? bySeverity : (a.priority ?? 99) - (b.priority ?? 99);
  });

  const evidenced = (evidence || []).filter((e) => e.evidenceStatus === 'HAS_EVIDENCE');

  return (
    <div className="gap-view">
      <div className="gap-list">
        {sorted.map((gap) => {
          const Icon = EVIDENCE_ICON[gap.evidenceStatus] || HelpCircle;
          return (
            <article key={gap.id} className={`gap-card gap-${(gap.severity || 'minor').toLowerCase()}`}>
              <header className="gap-card-header">
                <div>
                  <h4>{gap.skillLabel}</h4>
                  {gap.taxonomyCode && (
                    <span className="taxonomy-chip" title={`Reference taxonomy: ${gap.taxonomySource}`}>
                      {gap.taxonomyCode}
                      {gap.taxonomyName ? ` · ${gap.taxonomyName}` : ''}
                      <em>{gap.taxonomySource === 'SFIA9' ? 'SFIA 9' : 'extension'}</em>
                    </span>
                  )}
                </div>
                <div className="gap-levels">
                  {gap.severity && <span className="severity-pill">{gap.severity.toLowerCase()}</span>}
                  {gap.targetLevel != null && (
                    <span className="level-pill">
                      {gap.currentLevel != null ? `level ${gap.currentLevel} → ` : 'to level '}
                      {gap.targetLevel}
                    </span>
                  )}
                </div>
              </header>

              <p className={`evidence-line evidence-${(gap.evidenceStatus || '').toLowerCase()}`}>
                <Icon size={14} />
                {EVIDENCE_LABEL[gap.evidenceStatus] || 'Evidence status not stated'}
              </p>

              <p className="gap-rationale">{gap.rationale}</p>

              {gap.evidenceThatWouldSettleIt && (
                <p className="gap-settle">
                  <strong>What would change this:</strong> {gap.evidenceThatWouldSettleIt}
                </p>
              )}

              {gap.requirementEvidence?.length > 0 && (
                <details className="gap-source">
                  <summary>Where this requirement comes from</summary>
                  <ul>
                    {gap.requirementEvidence.map((line, i) => <li key={i}>{line}</li>)}
                  </ul>
                </details>
              )}

              {gap.confidence && (
                <p className="confidence-line">
                  Confidence in this assessment: {gap.confidence.toLowerCase()}
                </p>
              )}
            </article>
          );
        })}
      </div>

      {evidenced.length > 0 && (
        <details className="evidence-panel">
          <summary>What your profile already evidences ({evidenced.length})</summary>
          <ul className="evidence-list">
            {evidenced.map((item, index) => (
              <li key={`${item.skillLabel}-${index}`}>
                <div className="evidence-head">
                  <strong>{item.skillLabel}</strong>
                  {item.taxonomyCode && (
                    <span className="taxonomy-chip">
                      {item.taxonomyCode}
                      <em>{item.taxonomySource === 'SFIA9' ? 'SFIA 9' : 'extension'}</em>
                    </span>
                  )}
                  {item.assessedLevel != null && (
                    <span className="level-pill">level {item.assessedLevel}</span>
                  )}
                </div>
                {item.evidenceQuotes?.length > 0 && (
                  <ul className="quote-list">
                    {item.evidenceQuotes.map((quote, i) => <li key={i}>“{quote}”</li>)}
                  </ul>
                )}
                {item.levelBasis && <p className="muted-note">Level based on: {item.levelBasis}</p>}
              </li>
            ))}
          </ul>
        </details>
      )}

      {requirements?.length > 0 && (
        <details className="evidence-panel">
          <summary>What this role asks for ({requirements.length})</summary>
          <ul className="requirement-list">
            {requirements.map((req, index) => (
              <li key={`${req.skillLabel}-${index}`}>
                <strong>{req.skillLabel}</strong>
                {req.requiredLevel != null && (
                  <span className="level-pill">level {req.requiredLevel}</span>
                )}
                {req.importance && (
                  <span className="importance-pill">{req.importance.replace(/_/g, ' ').toLowerCase()}</span>
                )}
                {/* A requirement quoted from the advert the student pasted stands on different
                    ground from one the model judged, and the label says which. */}
                <span className={`source-pill source-${(req.sourceType || '').toLowerCase()}`}>
                  {req.sourceType === 'JOB_DESCRIPTION' && 'from the job advert'}
                  {req.sourceType === 'TAXONOMY' && 'from the reference taxonomy'}
                  {req.sourceType === 'AI_JUDGEMENT' && "AI's own judgement"}
                </span>
                {req.sourceNote && <p className="muted-note">{req.sourceNote}</p>}
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  );
}
