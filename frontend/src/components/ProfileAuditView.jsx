import { ThumbsUp, AlertCircle } from 'lucide-react';

/**
 * How the profile reads as a screening document.
 *
 * <p>Separate from the gap analysis because it answers a different question. The gaps say what to
 * learn; this says whether what the student has already done is legible to someone reading for
 * thirty seconds. A student can have no gaps worth mentioning and a document that gets them
 * filtered out, and the fix for that is rewriting, not studying.
 *
 * <p>The score is labelled at the point of display, every time. Left as a bare number it gets read
 * as a rating of the person, which is the one thing it is not.
 */
export default function ProfileAuditView({ audit }) {
  if (!audit) return null;

  const score = audit.healthScore ?? 0;
  const tone = score >= 75 ? 'score-high' : score >= 55 ? 'score-med' : 'score-low';

  return (
    <div className="audit-view">
      <div className="score-hero-card">
        <div className={`score-circle ${tone}`}>
          <span className="score-num">{score}</span>
          <span className="score-label">/ 100</span>
        </div>
        <div className="score-explain">
          <h4>{audit.verdict}</h4>
          <p>{audit.summary}</p>
          <p className="score-disclaimer">
            This scores your CV <em>as a document</em> — how complete, specific and easy to scan it
            is. It is not a rating of your ability and not a readiness score.
          </p>
        </div>
      </div>

      <div className="audit-columns">
        <div>
          <h4 className="phase-block-title"><ThumbsUp size={14} /> What this document does well</h4>
          <ul className="plain-list">
            {(audit.strengths || []).map((item, index) => <li key={index}>{item}</li>)}
          </ul>
        </div>
        <div>
          <h4 className="phase-block-title"><AlertCircle size={14} /> What a reader cannot tell yet</h4>
          <ul className="plain-list">
            {(audit.weaknesses || []).map((item, index) => <li key={index}>{item}</li>)}
          </ul>
        </div>
      </div>

      {(audit.atsKeywordsPresent?.length > 0 || audit.atsKeywordsMissing?.length > 0) && (
        <div className="phase-block">
          <h4 className="phase-block-title">Terms screeners look for in this role</h4>
          <div className="tag-container">
            {(audit.atsKeywordsPresent || []).map((keyword) => (
              <span key={`p-${keyword}`} className="skill-tag skill-tag-matched">{keyword}</span>
            ))}
            {(audit.atsKeywordsMissing || []).map((keyword) => (
              <span key={`m-${keyword}`} className="skill-tag skill-tag-missing">{keyword}</span>
            ))}
          </div>
          <p className="muted-note">
            Filled chips are already in your profile; outlined ones are not. Only add a term if it
            is true of you and you can point at where you did it.
          </p>
        </div>
      )}

      {audit.bulletImprovements?.length > 0 && (
        <div className="phase-block">
          <h4 className="phase-block-title">Lines worth rewriting</h4>
          {audit.bulletImprovements.map((bullet, index) => (
            <div key={index} className="star-card">
              <p className="star-before">{bullet.original}</p>
              <p className="star-after">{bullet.improved}</p>
              <p className="star-note">{bullet.rationale}</p>
            </div>
          ))}
          <p className="muted-note">
            Square brackets are placeholders for numbers only you know. Fill them in with real
            figures — never with invented ones.
          </p>
        </div>
      )}
    </div>
  );
}
