import { useState, useEffect, useCallback, useRef } from 'react';
import Header from './components/Header';
import ProfileInputPanel from './components/ProfileInputPanel';
import PlanWorkspace from './components/PlanWorkspace';
import CoachChatPanel from './components/CoachChatPanel';
import DataSourcePanel from './components/DataSourcePanel';
import {
  fetchCurrentProfile,
  cacheProfile,
  fetchPlan,
  generatePlan,
  updateProgress,
  DEFAULT_PROFILE,
  getApiBase,
  setCustomBackendUrl,
  waitForBackend,
  startKeepAlive,
  readCachedProfile,
} from './api';
import { CheckCircle2, AlertTriangle, Loader2, RefreshCw } from 'lucide-react';
import './App.css';
// The workspace layout lives in its own sheet: App.css carries the shared primitives (buttons,
// cards, form controls, chat bubbles) that both the page and the panels reuse.
import './workspace.css';

/**
 * Connection states.
 *
 * A free hosting tier sleeps the instance after 15 minutes idle and takes about a minute to wake.
 * Waking is not an error, so it gets its own state and a neutral banner; `offline` is reserved for
 * a genuine failure after the wake budget expires.
 */
const STATUS = {
  CONNECTING: 'connecting',
  ONLINE: 'online',
  OFFLINE: 'offline',
};

const THEME_KEY = 'AICAREER_THEME';

function initialTheme() {
  if (typeof window === 'undefined') return 'dark';
  try {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved === 'light' || saved === 'dark') return saved;
  } catch {
    // Fall through to the system preference when storage is unavailable.
  }
  return window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

const EMPTY_CHAT = { messages: [], loading: false, error: null };
const EMPTY_PLAN_STATE = {
  plan: null, completedItems: [], missingInputs: [], supersededSnapshot: false,
};

/**
 * One page: inputs on the left, the learning path on the right.
 *
 * <p>There is no tab bar and no second destination. Everything else the product does - the chat,
 * the data provenance - opens over this page rather than replacing it, so the path stays the thing
 * a visitor sees and the thing they come back to.
 */
export default function App() {
  const [profile, setProfile] = useState(() => readCachedProfile() || DEFAULT_PROFILE);
  const [planState, setPlanState] = useState(EMPTY_PLAN_STATE);
  const [status, setStatus] = useState(STATUS.CONNECTING);
  const [wakeSeconds, setWakeSeconds] = useState(0);
  const [backendInput, setBackendInput] = useState('');
  const [toast, setToast] = useState(null);
  const [theme, setTheme] = useState(initialTheme);
  const [generating, setGenerating] = useState(false);
  const [planError, setPlanError] = useState(null);
  const [busyItemId, setBusyItemId] = useState(null);
  const [chatOpen, setChatOpen] = useState(false);
  const [dataPanelOpen, setDataPanelOpen] = useState(false);
  const [chat, setChat] = useState(EMPTY_CHAT);
  const connectingRef = useRef(false);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    document.documentElement.style.colorScheme = theme;
    try {
      localStorage.setItem(THEME_KEY, theme);
    } catch {
      // The theme still works for this session when storage is unavailable.
    }
  }, [theme]);

  const connect = useCallback(async () => {
    if (connectingRef.current) return;
    connectingRef.current = true;
    setStatus(STATUS.CONNECTING);
    setWakeSeconds(0);

    try {
      const awake = await waitForBackend({
        onProgress: ({ elapsedSeconds }) => setWakeSeconds(elapsedSeconds),
      });
      if (!awake) {
        setStatus(STATUS.OFFLINE);
        return;
      }
      const [profileData, plan] = await Promise.all([fetchCurrentProfile(), fetchPlan()]);
      setProfile(profileData);
      setPlanState(plan);
      setStatus(STATUS.ONLINE);
    } catch (err) {
      console.warn('Backend unreachable:', err.message);
      setStatus(STATUS.OFFLINE);
    } finally {
      connectingRef.current = false;
    }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(connect, 0);
    return () => window.clearTimeout(timer);
  }, [connect]);

  // Hold the instance open while the user is working, so they pay the cold start at most once.
  useEffect(() => {
    if (status !== STATUS.ONLINE) return undefined;
    return startKeepAlive();
  }, [status]);

  const showToast = useCallback((message) => {
    setToast(message);
    setTimeout(() => setToast(null), 3500);
  }, []);

  /**
   * Refreshes the stored plan after the profile or goal changed.
   *
   * <p>A changed input does not delete the plan on screen by itself - the server decides whether
   * what it holds still answers the current question, and returns nothing when it does not. Doing
   * that check on the server means one rule, rather than the browser guessing at a second one.
   */
  const refreshPlan = useCallback(async () => {
    try {
      const plan = await fetchPlan();
      setPlanState(plan);
    } catch (err) {
      console.warn('Could not refresh the plan:', err.message);
    }
  }, []);

  const handleProfileUpdate = useCallback((updated) => {
    setProfile(updated);
    cacheProfile(updated);
    refreshPlan();
  }, [refreshPlan]);

  const handleGenerate = useCallback(async () => {
    setGenerating(true);
    setPlanError(null);
    try {
      const result = await generatePlan();
      setPlanState({
        plan: result.plan,
        completedItems: result.completedItems || [],
        missingInputs: [],
        supersededSnapshot: false,
      });
      showToast('Your learning path is ready.');
    } catch (err) {
      setPlanError({
        message: err.message,
        detail: err.detail,
        retryable: err.retryable !== false,
      });
      // The stored plan is left exactly as it was: a failed regeneration must not delete a plan
      // the student was working through.
    } finally {
      setGenerating(false);
    }
  }, [showToast]);

  /**
   * Records one tick.
   *
   * <p>Pessimistic on purpose: the checkbox only moves once the server has accepted it. An
   * optimistic tick that the server then rejects - stale plan, item gone - leaves a student
   * believing they have recorded something they have not.
   */
  const handleToggle = useCallback(async (itemId, completed) => {
    const planId = planState.plan?.planId;
    if (!planId) return;

    setBusyItemId(itemId);
    try {
      const result = await updateProgress(itemId, planId, completed);
      setPlanState((current) => ({ ...current, completedItems: result.completedItems }));
    } catch (err) {
      if (err.status === 409 || err.status === 404) {
        // The plan this tab is showing is not the current one any more. Pull the real one back so
        // the page reloads instead of accumulating clicks that cannot be saved.
        showToast(err.message);
        await refreshPlan();
      } else {
        showToast(err.message || 'Could not save your progress');
      }
    } finally {
      setBusyItemId(null);
    }
  }, [planState.plan?.planId, refreshPlan, showToast]);

  const handleConnectBackend = () => {
    if (!backendInput.trim()) return;
    setCustomBackendUrl(backendInput.trim());
    showToast('Backend URL saved. Reconnecting…');
    connect();
  };

  const isConnected = status === STATUS.ONLINE;

  return (
    <div className="app-container">
      <Header
        theme={theme}
        onToggleTheme={() => setTheme((t) => (t === 'light' ? 'dark' : 'light'))}
        onOpenChat={() => setChatOpen(true)}
        onOpenDataPanel={() => setDataPanelOpen(true)}
        chatAvailable={isConnected}
      />

      {status === STATUS.CONNECTING && (
        <div className="conn-banner conn-banner-waking">
          <Loader2 className="animate-spin" size={15} />
          <span>
            Connecting to the server…
            {wakeSeconds > 6 && (
              <>
                {' '}The server is waking up after being idle. The first request usually takes
                50-60 seconds — <strong>{wakeSeconds}s</strong>.
              </>
            )}
          </span>
        </div>
      )}

      {status === STATUS.OFFLINE && (
        <div className="conn-banner conn-banner-offline">
          <div className="conn-banner-msg">
            <AlertTriangle size={15} />
            <span>
              Could not reach the server at <code>{getApiBase()}</code>. Nothing can be generated
              until it answers.
            </span>
          </div>
          <div className="conn-banner-actions">
            <input
              type="text"
              placeholder="Paste backend URL (e.g. https://your-backend.onrender.com)"
              value={backendInput}
              onChange={(e) => setBackendInput(e.target.value)}
              className="conn-input"
            />
            <button onClick={handleConnectBackend} className="btn btn-primary btn-sm">
              Connect
            </button>
            <button onClick={connect} title="Retry" className="btn btn-sm conn-retry">
              <RefreshCw size={13} />
            </button>
          </div>
        </div>
      )}

      <section className="hero">
        <h1 className="hero-title">
          Build a learning path that fits <em>your</em> profile and the job you want
        </h1>
        <p className="hero-subtitle">
          Upload your CV or type it in, say what you are aiming at, and get a phased plan — which
          skills to learn, in what order, how many hours each takes, and where to learn them.
        </p>
      </section>

      <main className="workspace">
        <div className="workspace-input">
          <ProfileInputPanel
            profile={profile}
            setProfile={handleProfileUpdate}
            onGenerate={handleGenerate}
            generating={generating}
            isConnected={isConnected}
            showToast={showToast}
            missingInputs={planState.missingInputs}
          />
        </div>

        <div className="workspace-plan">
          <PlanWorkspace
            planState={planState}
            generating={generating}
            error={planError}
            onToggle={handleToggle}
            busyItemId={busyItemId}
            onRetry={handleGenerate}
            isConnected={isConnected}
          />
        </div>
      </main>

      <CoachChatPanel
        open={chatOpen}
        onClose={() => setChatOpen(false)}
        chat={chat}
        setChat={setChat}
        hasPlan={Boolean(planState.plan)}
      />

      {/* Mounted only while open, so it reads the server each time it is opened rather than
          showing whatever it happened to fetch the first time. */}
      {dataPanelOpen && <DataSourcePanel onClose={() => setDataPanelOpen(false)} />}

      {toast && (
        <div className="toast">
          <CheckCircle2 size={18} style={{ color: 'var(--accent-emerald)' }} />
          <span>{toast}</span>
        </div>
      )}
    </div>
  );
}
