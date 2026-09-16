import { useEffect, useState } from 'react';
import { X, CheckCircle2, AlertTriangle, Loader2 } from 'lucide-react';
import { fetchDataStatus } from '../api';

/**
 * What reference data the system actually holds, and which models are configured.
 *
 * <p>Here because the honest answer to "is your SFIA mapping real" is a number, and a missing
 * licensed file has to be visible rather than inferred from skill codes that quietly stopped
 * appearing. It is also the fastest way to tell a working API key from one that is merely present.
 */
export default function DataSourcePanel({ onClose }) {
  // Mounted only while the panel is open, so the fetch belongs to the mount rather than to a prop
  // change. `loading` starts true for the same reason: setting it inside the effect body would
  // render one frame of "nothing here" before the first frame that is actually accurate.
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    fetchDataStatus()
      .then((result) => {
        if (!cancelled) setStatus(result);
      })
      .catch((err) => {
        if (!cancelled) setError(err.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const onKey = (event) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const taxonomy = status?.taxonomy;
  const retrieval = status?.retrieval;
  const ai = status?.ai;

  return (
    <>
      <div className="panel-scrim" onClick={onClose} aria-hidden="true" />
      <aside className="data-panel" role="dialog" aria-label="Sources and data">
        <header className="chat-panel-header">
          <div>
            <h3>Sources &amp; data</h3>
            <p className="muted-note">What every analysis on this site is built from.</p>
          </div>
          <button type="button" className="modal-close-btn" onClick={onClose} aria-label="Close">
            <X size={18} />
          </button>
        </header>

        <div className="data-panel-body">
          {loading && <p><Loader2 className="animate-spin" size={15} /> Reading the server…</p>}
          {error && <div className="inline-error"><AlertTriangle size={15} /><p>{error}</p></div>}

          {taxonomy && (
            <section className="data-block">
              <h4>Skill taxonomy</h4>
              <p className={taxonomy.sfiaLoaded ? 'status-ok' : 'status-warn'}>
                {taxonomy.sfiaLoaded
                  ? <><CheckCircle2 size={14} /> SFIA 9 loaded — {taxonomy.sfiaSkillCount} skills
                    {taxonomy.sfiaDatasetVersion ? ` from ${taxonomy.sfiaDatasetVersion}` : ''}</>
                  : <><AlertTriangle size={14} /> SFIA 9 is not loaded</>}
              </p>
              {!taxonomy.sfiaLoaded && (
                <div className="data-help">
                  <p>{taxonomy.sfiaUnavailableReason}</p>
                  <p>
                    SFIA is licensed content, free for personal career development and most internal
                    use, so this project ships no copy. To load it: register at{' '}
                    <a href="https://sfia-online.org/en/sfia-9/documentation" target="_blank" rel="noopener noreferrer">
                      sfia-online.org
                    </a>
                    , download the SFIA 9 skill descriptions workbook (.xlsx), and put it in{' '}
                    <code>{taxonomy.sfiaExpectedDirectory}</code>.
                  </p>
                  <p className="muted-note">
                    Until then, skills are normalised against this project's own technology
                    vocabulary. Codes shown are not official SFIA codes, and every plan generated
                    meanwhile records that limitation.
                  </p>
                </div>
              )}
              <p className="muted-note">
                {taxonomy.extensionSkillCount} technology entries — concrete tools that SFIA
                deliberately does not enumerate, kept separate from the framework itself.
              </p>
            </section>
          )}

          {retrieval && (
            <section className="data-block">
              <h4>Retrieval corpus</h4>
              <p className="status-ok">
                <CheckCircle2 size={14} /> {retrieval.documentCount} learning resources
                {retrieval.corpusVersion ? ` (catalogue ${retrieval.corpusVersion})` : ''}
              </p>
              <p className="muted-note">
                {retrieval.urlsVerifiedReachable} of {retrieval.documentCount} links answered when
                last checked. A reachable link means the page exists; it does not prove the content
                suits your level or is still free. Some hosts refuse automated requests, so a link
                that is not counted here has not necessarily gone anywhere.
              </p>
              <p className="muted-note">
                Plans may cite only documents retrieved from this catalogue. A citation to anything
                else is rejected before you ever see it.
              </p>
            </section>
          )}

          {ai && (
            <section className="data-block">
              <h4>AI models</h4>
              <p className={ai.apiKeyConfigured ? 'status-ok' : 'status-warn'}>
                {ai.apiKeyConfigured
                  ? <><CheckCircle2 size={14} /> API key configured</>
                  : <><AlertTriangle size={14} /> No API key configured</>}
              </p>
              <ul className="plain-list">
                <li>Primary: <code>{ai.primaryModel || 'not set'}</code></li>
                <li>Fallback: <code>{ai.fallbackModel || 'not set'}</code></li>
                {ai.lastWorkingModel && <li>Last model to answer: <code>{ai.lastWorkingModel}</code></li>}
              </ul>
              {ai.lastError && (
                <p className="status-warn"><AlertTriangle size={14} /> Last error: {ai.lastError}</p>
              )}
              <p className="muted-note">
                These two models are the only ones contacted. When neither answers, nothing is
                generated and the page says so — no written-in-advance analysis stands in.
              </p>
            </section>
          )}
        </div>
      </aside>
    </>
  );
}
