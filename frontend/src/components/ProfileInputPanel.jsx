import { useRef, useState } from 'react';
import {
  Upload, FileText, PencilLine, Loader2, X, Plus, Target, Clock, AlertTriangle, Sparkles,
} from 'lucide-react';
import {
  saveProfile, uploadCvFile, resetSampleProfile,
  SENIORITY_OPTIONS, DURATION_OPTIONS, MAX_HOURS_PER_WEEK,
} from '../api';

/**
 * Identity of the profile revision the form is showing. The server moves `updatedAt` only on a
 * real content change, so this is exactly the signal for "these fields describe someone else now".
 */
function profileStampOf(profile) {
  return `${profile.id}|${profile.updatedAt}`;
}

/**
 * Identity of the stored goal. Tracked separately from the profile stamp because saving a goal
 * deliberately does not move the profile revision - the CV has not changed just because the
 * student picked a different target role.
 */
function goalStampOf(profile) {
  return [
    profile.targetRole || '',
    profile.targetSeniority || '',
    profile.planDurationMonths ?? '',
    profile.planHoursPerWeek ?? '',
    (profile.targetJobDescription || '').length,
  ].join('|');
}

/** The goal form's starting values. Defaults are offered, never saved until the student acts. */
function goalFrom(profile) {
  return {
    targetRole: profile.targetRole || '',
    targetSeniority: profile.targetSeniority || '',
    targetJobDescription: profile.targetJobDescription || '',
    planDurationMonths: profile.planDurationMonths ?? 3,
    planHoursPerWeek: profile.planHoursPerWeek ?? 8,
  };
}

/**
 * The input column: the profile, then the goal, then the button that builds the path.
 *
 * <p>Both ways of supplying a profile live behind tabs in the same panel rather than on separate
 * screens, because they are the same step: a student who uploads a CV still has to check what was
 * extracted, and one who types it in is doing the extraction themselves. After an upload the form
 * is shown filled in, editable, and labelled as what the model read - not as fact.
 *
 * <p>The goal fields are below the profile and not optional. Skill gaps only exist relative to
 * something; without a target role and a time budget there is nothing to compute, and the generate
 * button says which piece is still missing rather than failing after a minute of work.
 */
export default function ProfileInputPanel({
  profile, setProfile, onGenerate, generating, isConnected, showToast, missingInputs,
}) {
  const [mode, setMode] = useState('upload');
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState(null);
  const [saving, setSaving] = useState(false);
  const [skillDraft, setSkillDraft] = useState('');
  const fileInputRef = useRef(null);

  // The form edits a local copy so typing does not fire a request per keystroke. It is re-seeded
  // whenever the server hands back a different profile - an upload, a sample, a reconnect.
  //
  // Re-seeding happens during render rather than in an effect. Adjusting state from changed props
  // in an effect renders the stale value first and the corrected one immediately after, which for
  // a form means the user can see and type into fields that are about to be replaced.
  const [form, setForm] = useState(profile);
  const [formSeed, setFormSeed] = useState(profileStampOf(profile));
  if (formSeed !== profileStampOf(profile)) {
    setFormSeed(profileStampOf(profile));
    setForm(profile);
  }

  // Goal edits are kept separate from profile edits: changing the target role must not look like
  // an edit to the CV, which would discard a plan the student is working through.
  const [goal, setGoal] = useState(() => goalFrom(profile));
  const [goalSeed, setGoalSeed] = useState(goalStampOf(profile));
  if (goalSeed !== goalStampOf(profile)) {
    setGoalSeed(goalStampOf(profile));
    setGoal(goalFrom(profile));
  }

  const [showJobDescription, setShowJobDescription] = useState(
    Boolean(profile.targetJobDescription));

  const handleFile = async (file) => {
    if (!file) return;
    setUploading(true);
    setUploadError(null);
    try {
      const updated = await uploadCvFile(file);
      setProfile(updated);
      setForm(updated);
      setMode('review');
      showToast('CV read. Check what was extracted before building your path.');
    } catch (err) {
      // The server distinguishes an unreadable file from an unavailable model, and the two need
      // different things from the user, so the message is shown as it came rather than flattened.
      setUploadError({ message: err.message, detail: err.detail, retryable: err.retryable });
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const persist = async (payload, successMessage) => {
    setSaving(true);
    try {
      const updated = await saveProfile(payload);
      setProfile(updated);
      if (successMessage) showToast(successMessage);
      return updated;
    } catch (err) {
      showToast(err.message || 'Could not save');
      return null;
    } finally {
      setSaving(false);
    }
  };

  const saveDetails = () => persist({
    fullName: form.fullName,
    email: form.email,
    phone: form.phone,
    currentTitle: form.currentTitle,
    industry: form.industry,
    yearsOfExperience: Number(form.yearsOfExperience) || 0,
    bio: form.bio,
    skills: form.skills,
    education: form.education,
    languages: form.languages,
  }, 'Your details are saved.');

  /**
   * Saves the goal and then builds. One action rather than two, because a student who has just
   * typed a target role and pressed the button has already said what they want; making them save
   * first was a step that existed only to suit the data model.
   */
  const saveGoalAndGenerate = async () => {
    const saved = await persist({
      targetRole: goal.targetRole,
      targetSeniority: goal.targetSeniority,
      targetJobDescription: showJobDescription ? goal.targetJobDescription : '',
      planDurationMonths: Number(goal.planDurationMonths),
      planHoursPerWeek: Number(goal.planHoursPerWeek),
    });
    if (saved) {
      onGenerate();
    }
  };

  const addSkill = () => {
    const value = skillDraft.trim();
    if (!value) return;
    // A profile restored from an old browser cache can arrive without the list at all.
    const current = form.skills || [];
    if (!current.some((s) => s.toLowerCase() === value.toLowerCase())) {
      setForm({ ...form, skills: [...current, value] });
    }
    setSkillDraft('');
  };

  const loadSample = async (type) => {
    try {
      const updated = await resetSampleProfile(type);
      setProfile(updated);
      setForm(updated);
      setMode('review');
      showToast('Sample profile loaded.');
    } catch (err) {
      showToast(err.message || 'Could not load the sample');
    }
  };

  const hasProfileContent = (form.skills && form.skills.length > 0) || Boolean(form.rawCvText);
  const goalComplete = goal.targetRole.trim() && goal.targetSeniority
    && goal.planDurationMonths && goal.planHoursPerWeek;
  const canGenerate = isConnected && !generating && !saving && hasProfileContent && goalComplete;

  return (
    <div className="input-panel">
      <section className="glass-card input-section">
        <div className="card-title-row">
          <h2 className="card-title">
            <span className="step-number">1</span>
            Your profile
          </h2>
        </div>

        <div className="segmented">
          <button
            type="button"
            className={`segmented-btn ${mode === 'upload' ? 'active' : ''}`}
            onClick={() => setMode('upload')}
          >
            <Upload size={14} /> Upload a CV
          </button>
          <button
            type="button"
            className={`segmented-btn ${mode !== 'upload' ? 'active' : ''}`}
            onClick={() => setMode('review')}
          >
            <PencilLine size={14} /> Enter it manually
          </button>
        </div>

        {mode === 'upload' ? (
          <div className="upload-area">
            <label
              className="dropzone"
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                e.preventDefault();
                handleFile(e.dataTransfer.files?.[0]);
              }}
            >
              <input
                ref={fileInputRef}
                type="file"
                accept=".pdf,.txt"
                hidden
                onChange={(e) => handleFile(e.target.files?.[0])}
                disabled={uploading || !isConnected}
              />
              <div className="dropzone-icon">
                {uploading ? <Loader2 className="animate-spin" size={22} /> : <FileText size={22} />}
              </div>
              <strong>{uploading ? 'Reading your CV…' : 'Drop a PDF here, or click to choose'}</strong>
              <span className="muted-note">
                The PDF needs selectable text. A scanned image has nothing to read.
              </span>
            </label>

            {uploadError && (
              <div className="inline-error">
                <AlertTriangle size={15} />
                <div>
                  <p>{uploadError.message}</p>
                  {uploadError.detail && <p className="inline-error-detail">{uploadError.detail}</p>}
                </div>
              </div>
            )}

            <div className="sample-row">
              <span className="muted-note">No CV to hand?</span>
              <button type="button" className="link-btn" onClick={() => loadSample('student-early')}>
                Load an early-year sample
              </button>
              <button type="button" className="link-btn" onClick={() => loadSample('student-final')}>
                Load a final-year sample
              </button>
            </div>
          </div>
        ) : (
          <div className="manual-form">
            {form.rawCvText && (
              <p className="extraction-note">
                These fields are what the AI read from your CV. Correct anything it got wrong —
                the whole analysis is built on them.
              </p>
            )}

            <div className="field-grid">
              <div className="form-group">
                <label className="form-label">Name</label>
                <input
                  className="form-control"
                  value={form.fullName || ''}
                  onChange={(e) => setForm({ ...form, fullName: e.target.value })}
                />
              </div>
              <div className="form-group">
                <label className="form-label">Field or industry</label>
                <input
                  className="form-control"
                  placeholder="e.g. Software Engineering, IC Design"
                  value={form.industry || ''}
                  onChange={(e) => setForm({ ...form, industry: e.target.value })}
                />
              </div>
              <div className="form-group">
                <label className="form-label">Current title or course</label>
                <input
                  className="form-control"
                  value={form.currentTitle || ''}
                  onChange={(e) => setForm({ ...form, currentTitle: e.target.value })}
                />
              </div>
              <div className="form-group">
                <label className="form-label">Years of professional experience</label>
                <input
                  className="form-control"
                  type="number"
                  min="0"
                  step="0.5"
                  value={form.yearsOfExperience ?? 0}
                  onChange={(e) => setForm({ ...form, yearsOfExperience: e.target.value })}
                />
              </div>
            </div>

            <div className="form-group">
              <label className="form-label">Skills and tools</label>
              <div className="tag-container">
                {(form.skills || []).map((skill) => (
                  <span key={skill} className="skill-tag">
                    {skill}
                    <button
                      type="button"
                      className="skill-tag-remove"
                      onClick={() => setForm({
                        ...form, skills: form.skills.filter((s) => s !== skill),
                      })}
                      aria-label={`Remove ${skill}`}
                    >
                      <X size={12} />
                    </button>
                  </span>
                ))}
              </div>
              <div className="inline-add">
                <input
                  className="form-control"
                  placeholder="Add a skill and press Enter"
                  value={skillDraft}
                  onChange={(e) => setSkillDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      addSkill();
                    }
                  }}
                />
                <button type="button" className="btn btn-outline btn-sm" onClick={addSkill}>
                  <Plus size={14} />
                </button>
              </div>
            </div>

            <div className="form-group">
              <label className="form-label">Education</label>
              <input
                className="form-control"
                value={form.education || ''}
                onChange={(e) => setForm({ ...form, education: e.target.value })}
              />
            </div>

            <div className="form-group">
              <label className="form-label">Projects, experience and anything else worth knowing</label>
              <textarea
                className="form-control"
                rows={5}
                placeholder="What you built, what you did on it, what it produced. Specifics here are what the analysis has to work with."
                value={form.bio || ''}
                onChange={(e) => setForm({ ...form, bio: e.target.value })}
              />
            </div>

            <button
              type="button"
              className="btn btn-outline"
              onClick={saveDetails}
              disabled={saving || !isConnected}
            >
              {saving ? <Loader2 className="animate-spin" size={15} /> : null}
              Save these details
            </button>
          </div>
        )}
      </section>

      <section className="glass-card input-section">
        <div className="card-title-row">
          <h2 className="card-title">
            <span className="step-number">2</span>
            What you are aiming at
          </h2>
        </div>

        <div className="form-group">
          <label className="form-label">
            <Target size={13} /> Target role
          </label>
          <input
            className="form-control"
            placeholder="e.g. Backend Developer, Data Analyst, Verification Engineer"
            value={goal.targetRole}
            onChange={(e) => setGoal({ ...goal, targetRole: e.target.value })}
            list="role-suggestions"
          />
          {profile.targetRoles?.length > 0 && (
            <datalist id="role-suggestions">
              {profile.targetRoles.map((role) => <option key={role} value={role} />)}
            </datalist>
          )}
        </div>

        <div className="form-group">
          <label className="form-label">Level you are aiming at</label>
          <div className="choice-row">
            {SENIORITY_OPTIONS.map((option) => (
              <button
                key={option.value}
                type="button"
                className={`choice-chip ${goal.targetSeniority === option.value ? 'active' : ''}`}
                onClick={() => setGoal({ ...goal, targetSeniority: option.value })}
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>

        <div className="field-grid">
          <div className="form-group">
            <label className="form-label">How long is the plan?</label>
            <div className="choice-row">
              {DURATION_OPTIONS.map((months) => (
                <button
                  key={months}
                  type="button"
                  className={`choice-chip ${Number(goal.planDurationMonths) === months ? 'active' : ''}`}
                  onClick={() => setGoal({ ...goal, planDurationMonths: months })}
                >
                  {months} {months === 1 ? 'month' : 'months'}
                </button>
              ))}
            </div>
          </div>

          <div className="form-group">
            <label className="form-label">
              <Clock size={13} /> Hours you can study each week
            </label>
            <div className="hours-row">
              <input
                type="range"
                min="1"
                max={MAX_HOURS_PER_WEEK}
                value={goal.planHoursPerWeek}
                onChange={(e) => setGoal({ ...goal, planHoursPerWeek: Number(e.target.value) })}
                className="hours-slider"
              />
              <span className="hours-value">{goal.planHoursPerWeek}h</span>
            </div>
            <span className="muted-note">
              Be realistic. Everything in the plan is sized against this number.
            </span>
          </div>
        </div>

        <button
          type="button"
          className="link-btn jd-toggle"
          onClick={() => setShowJobDescription((v) => !v)}
        >
          {showJobDescription ? 'Remove the job description' : 'Paste a job description (optional)'}
        </button>
        {showJobDescription && (
          <div className="form-group">
            <textarea
              className="form-control"
              rows={5}
              placeholder="Paste the advert you are aiming at. Requirements taken from it are labelled as coming from the advert rather than from a general idea of the role."
              value={goal.targetJobDescription || ''}
              onChange={(e) => setGoal({ ...goal, targetJobDescription: e.target.value })}
            />
          </div>
        )}
      </section>

      <div className="generate-block">
        <button
          type="button"
          className="btn btn-primary btn-generate"
          onClick={saveGoalAndGenerate}
          disabled={!canGenerate}
        >
          {generating
            ? <><Loader2 className="animate-spin" size={17} /> Building your path…</>
            : <><Sparkles size={17} /> Build my learning path</>}
        </button>

        {!canGenerate && !generating && (
          <p className="generate-hint">
            {!isConnected
              ? 'Waiting for the server.'
              : missingInputs?.length
                ? `Still needed: ${missingInputs.join(', ')}.`
                : !hasProfileContent
                  ? 'Add your skills or upload a CV first.'
                  : 'Fill in the target role, level, length and weekly hours.'}
          </p>
        )}
      </div>
    </div>
  );
}
