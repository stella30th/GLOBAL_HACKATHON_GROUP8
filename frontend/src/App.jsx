import React, { useState, useEffect, useCallback, useRef } from 'react';
import Navbar from './components/Navbar';
import ProfileView from './components/ProfileView';
import JobMatchingView from './components/JobMatchingView';
import ResumeAuditView from './components/ResumeAuditView';
import AiCoachChatView from './components/AiCoachChatView';
import {
  fetchCurrentProfile,
  DEFAULT_PROFILE,
  getApiBase,
  setCustomBackendUrl,
  waitForBackend,
  startKeepAlive,
  readCachedProfile,
} from './api';
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

export default function App() {
  const [activeTab, setActiveTab] = useState('matching');
  // Show the last known profile immediately so the UI is populated while the server wakes.
  const [profile, setProfile] = useState(() => readCachedProfile() || DEFAULT_PROFILE);
  const [status, setStatus] = useState(STATUS.CONNECTING);
  const [wakeSeconds, setWakeSeconds] = useState(0);
  const [backendInput, setBackendInput] = useState('');
  const [toast, setToast] = useState(null);
  const connectingRef = useRef(false);

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

  const showToast = (message) => {
    setToast(message);
    setTimeout(() => setToast(null), 3500);
  };

  const handleConnectBackend = () => {
    if (!backendInput.trim()) return;
    setCustomBackendUrl(backendInput.trim());
    showToast('Đã lưu URL backend. Đang kết nối lại...');
    connect();
  };

  const isConnected = status === STATUS.ONLINE;

  return (
    <div className="app-container">
      <Navbar activeTab={activeTab} setActiveTab={setActiveTab} isConnected={isConnected} />

      {status === STATUS.CONNECTING && (
        <div className="conn-banner conn-banner-waking">
          <Loader2 className="animate-spin" size={15} />
          <span>
            Đang kết nối máy chủ…
            {wakeSeconds > 6 && (
              <>
                {' '}Máy chủ đang khởi động lại sau thời gian nghỉ (gói Render Free tự tắt sau 15 phút
                không dùng). Lần đầu thường mất khoảng 50–60 giây — <strong>{wakeSeconds}s</strong>.
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
              Không kết nối được máy chủ tại <code>{getApiBase()}</code>. Đang chạy ở chế độ xem offline.
            </span>
          </div>
          <div className="conn-banner-actions">
            <input
              type="text"
              placeholder="Dán URL backend (vd: https://aicareer-backend-xxxx.onrender.com)"
              value={backendInput}
              onChange={(e) => setBackendInput(e.target.value)}
              className="conn-input"
            />
            <button onClick={handleConnectBackend} className="btn btn-primary btn-sm">
              Kết nối
            </button>
            <button onClick={connect} title="Thử lại" className="btn btn-sm conn-retry">
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
            onGoToMatching={() => setActiveTab('matching')}
            showToast={showToast}
          />
        )}

        {activeTab === 'matching' && (
          <JobMatchingView profile={profile} showToast={showToast} isConnected={isConnected} />
        )}

        {activeTab === 'audit' && <ResumeAuditView profile={profile} isConnected={isConnected} />}

        {activeTab === 'chat' && <AiCoachChatView profile={profile} isConnected={isConnected} />}
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
