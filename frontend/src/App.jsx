import { useState, useEffect, useCallback, useRef } from 'react';
import Navbar from './components/Navbar';
import ProfileView from './components/ProfileView';
import JobMatchingView from './components/JobMatchingView';
import ResumeAuditView from './components/ResumeAuditView';
import AiCoachChatView from './components/AiCoachChatView';
import {
  fetchCurrentProfile,
  cacheProfile,
  updateMilestoneProgress,
  DEFAULT_PROFILE,
  getApiBase,
  setCustomBackendUrl,
  waitForBackend,
  startKeepAlive,
  readCachedProfile,
} from './api';
import {
  createJobPracticeRequest,
  createRoadmapPracticeRequest,
  profileRevisionOf,
} from './practice';
import { CheckCircle2, AlertTriangle, Loader2, RefreshCw } from 'lucide-react';
import './App.css';

/**
 * Connection states.
 *
 * The app used to show a red "backend not reached" banner the moment its 8 second request failed,
 * which on Render's free tier is the normal first-visit experience: the instance sleeps after 15
 * minutes idle and takes around a minute to wake. Waking is not an error, so it gets its own state
 * and a neutral banner; `offline` is reserved for a genuine failure after the wake budget expires.
 */
const STATUS = {
  CONNECTING: 'connecting',
  ONLINE: 'online',
  OFFLINE: 'offline',
};

const EMPTY_CHAT = { sessionId: 'initial', messages: [], loading: false, context: null, pendingRetry: null };

export default function App() {
  const [activeTab, setActiveTab] = useState('profile');
  // Show the last known profile immediately so the UI is populated while the server wakes.
  const [profile, setProfile] = useState(() => readCachedProfile() || DEFAULT_PROFILE);
  const [status, setStatus] = useState(STATUS.CONNECTING);
  const [wakeSeconds, setWakeSeconds] = useState(0);
  const [backendInput, setBackendInput] = useState('');
  const [toast, setToast] = useState(null);
  const connectingRef = useRef(false);

  /**
   * Chat and practice state live here rather than inside the chat view, because switching tabs
   * unmounts the view. A conversation the user is midway through must survive a trip to the
   * roadmap and back; only a page reload starts a new one.
   */
  const [chat, setChat] = useState(EMPTY_CHAT);
  const [practiceRequest, setPracticeRequest] = useState(null);

  const revision = profileRevisionOf(profile);
  const lastRevisionRef = useRef(revision);
  // Mirrors `revision` for callbacks that must not close over a stale render.
  const revisionRef = useRef(revision);
  // Tail of the progress-update chain. Ticks queue behind each other rather than racing.
  const progressQueueRef = useRef(Promise.resolve());

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
      const data = await fetchCurrentProfile();
      setProfile(data);
      setStatus(STATUS.ONLINE);
    } catch (err) {
      console.warn('Backend unreachable:', err.message);
      setStatus(STATUS.OFFLINE);
    } finally {
      connectingRef.current = false;
    }
  }, []);

  useEffect(() => {
    connect();
  }, [connect]);

  // Hold the instance open while the user is working, so they pay the cold start at most once.
  useEffect(() => {
    if (status !== STATUS.ONLINE) return undefined;
    return startKeepAlive();
  }, [status]);

  // Kept in an effect rather than assigned during render: event handlers only ever run after the
  // commit, so they still see the revision that is on screen.
  useEffect(() => {
    revisionRef.current = revision;
  }, [revision]);

  /**
   * A real profile edit changes the revision, which means the analysis, the roadmap and the
   * advice the coach has been giving all describe someone the user no longer is. Reset the chat
   * rather than letting it continue from a premise that has changed underneath it. Ticking a
   * milestone does not move the revision, so it does not land here.
   */
  useEffect(() => {
    if (lastRevisionRef.current === revision) return;
    lastRevisionRef.current = revision;
    setChat({ ...EMPTY_CHAT, sessionId: `rev-${revision}` });
    setPracticeRequest(null);
  }, [revision]);

  const showToast = (message) => {
    setToast(message);
    setTimeout(() => setToast(null), 3500);
  };

  const handleConnectBackend = () => {
    if (!backendInput.trim()) return;
    setCustomBackendUrl(backendInput.trim());
    showToast('Backend URL saved. Reconnecting...');
    connect();
  };

  const startRoadmapPractice = (milestone, roadmapId) => {
    setPracticeRequest(createRoadmapPracticeRequest(profile, milestone, roadmapId));
    setActiveTab('chat');
  };

  const startJobPractice = (job, question) => {
    setPracticeRequest(createJobPracticeRequest(profile, job, question));
    setActiveTab('chat');
  };

  /**
   * Pessimistic: the checkbox only moves once the server has accepted it. An optimistic tick that
   * the server then rejects (stale roadmap, milestone gone) leaves the user believing they have
   * recorded something they have not.
   *
   * <p>Updates are queued one behind another. Each response carries the server's whole progress
   * list, so two ticks in flight together could land out of order and let the earlier, shorter
   * list overwrite the later one — the database stayed correct while the screen and the cached
   * profile quietly lost a tick. Only one checkbox is disabled at a time by design, so ticking
   * three boxes quickly is ordinary use, not an edge case.
   */
  const toggleMilestone = useCallback((milestoneId, roadmapId, completed) => {
    const revisionAtRequest = revisionRef.current;

    const run = progressQueueRef.current.then(async () => {
      try {
        const updated = await updateMilestoneProgress(milestoneId, roadmapId, completed);
        // The profile was replaced while this was in flight (an edit, a CV upload, a sample), so
        // this progress list belongs to a roadmap that no longer exists. Nothing about it is kept
        // — not the screen, and not the browser cache the next page load reads from.
        if (revisionRef.current !== revisionAtRequest) {
          return { ok: false, reload: true, message: 'Your profile changed, so this was not applied.' };
        }
        setProfile(updated);
        cacheProfile(updated);
        return { ok: true };
      } catch (err) {
        if (err.status === 409 || err.status === 404) {
          // The roadmap this tab is showing is not the current one any more. Pull the real profile
          // back so the roadmap reloads instead of accumulating more clicks that cannot be saved.
          try {
            const fresh = await fetchCurrentProfile(undefined, { cache: false });
            if (revisionRef.current === revisionAtRequest) {
              setProfile(fresh);
              cacheProfile(fresh);
            }
          } catch {
            // Leave the stale profile in place; the message below still explains what happened.
          }
          showToast(err.message);
          return { ok: false, reload: true, message: err.message };
        }
        showToast(err.message || 'Could not save your progress');
        return { ok: false, reload: false, message: err.message };
      }
    });

    // The queue must survive a rejection, or one failure would stall every later tick.
    progressQueueRef.current = run.catch(() => {});
    return run;
  }, []);

  const isConnected = status === STATUS.ONLINE;

  return (
    <div className="app-container">
      <Navbar activeTab={activeTab} setActiveTab={setActiveTab} isConnected={isConnected} />

      {status === STATUS.CONNECTING && (
        <div className="conn-banner conn-banner-waking">
          <Loader2 className="animate-spin" size={15} />
          <span>
            Connecting to the server…
            {wakeSeconds > 6 && (
              <>
                {' '}The server is waking up after being idle (the Render free plan sleeps after 15
                minutes). The first request usually takes 50-60 seconds — <strong>{wakeSeconds}s</strong>.
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
              Could not reach the server at <code>{getApiBase()}</code>. Running in offline preview mode.
            </span>
          </div>
          <div className="conn-banner-actions">
            <input
              type="text"
              placeholder="Paste backend URL (e.g. https://aicareer-backend-xxxx.onrender.com)"
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

      <main className="main-content">
        {activeTab === 'profile' && (
          <ProfileView
            profile={profile}
            setProfile={setProfile}
            onGoToRoadmap={() => setActiveTab('audit')}
            onGoToMatching={() => setActiveTab('matching')}
            showToast={showToast}
          />
        )}

        {activeTab === 'matching' && (
          <JobMatchingView
            profile={profile}
            showToast={showToast}
            isConnected={isConnected}
            onPracticeQuestion={startJobPractice}
          />
        )}

        {activeTab === 'audit' && (
          <ResumeAuditView
            profile={profile}
            isConnected={isConnected}
            onPracticeMilestone={startRoadmapPractice}
            onToggleMilestone={toggleMilestone}
          />
        )}

        {activeTab === 'chat' && (
          <AiCoachChatView
            profile={profile}
            isConnected={isConnected}
            chat={chat}
            setChat={setChat}
            practiceRequest={practiceRequest}
            onPracticeConsumed={() => setPracticeRequest(null)}
            onBackToRoadmap={() => setActiveTab('audit')}
            onToggleMilestone={toggleMilestone}
            profileRevision={revision}
          />
        )}
      </main>

      {toast && (
        <div className="toast">
          <CheckCircle2 size={18} color="#10b981" />
          <span>{toast}</span>
        </div>
      )}
    </div>
  );
}
