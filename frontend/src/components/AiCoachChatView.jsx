import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Send, Bot, User, Sparkles, Loader2, Dumbbell, ArrowLeft, CheckSquare, RotateCcw } from 'lucide-react';
import { sendChatMessage } from '../api';
import { buildPracticeContextBlock, practiceLabel } from '../practice';

/**
 * Inline markdown, rendered as React elements rather than assembled into an HTML string.
 *
 * The previous version built markup and passed it to dangerouslySetInnerHTML. Everything on this
 * screen traces back to a CV, a scraped job posting or a model's output, so a posting containing
 * a script tag would have executed inside the app. Returning elements makes that impossible:
 * React escapes the text nodes and there is no HTML to inject into.
 */
function renderInline(text, keyPrefix) {
  const parts = String(text).split(/(\*\*[^*]+\*\*|\*[^*\n]+\*|`[^`]+`)/g);
  return parts.filter(Boolean).map((part, idx) => {
    const key = `${keyPrefix}-${idx}`;
    if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
      return <strong key={key}>{part.slice(2, -2)}</strong>;
    }
    if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
      return (
        <code
          key={key}
          style={{ background: 'rgba(255,255,255,0.1)', padding: '2px 5px', borderRadius: '4px', fontFamily: 'monospace' }}
        >
          {part.slice(1, -1)}
        </code>
      );
    }
    if (part.startsWith('*') && part.endsWith('*') && part.length > 2) {
      return <em key={key}>{part.slice(1, -1)}</em>;
    }
    return <React.Fragment key={key}>{part}</React.Fragment>;
  });
}

function renderFormattedContent(content) {
  return String(content ?? '').split('\n').map((line, idx) => {
    if (line.startsWith('### ')) {
      return <h3 key={idx} style={{ color: '#fff', margin: '0.6rem 0 0.3rem', fontSize: '1.05rem' }}>{renderInline(line.slice(4), idx)}</h3>;
    }
    if (line.startsWith('## ')) {
      return <h2 key={idx} style={{ color: '#fff', margin: '0.8rem 0 0.4rem', fontSize: '1.2rem' }}>{renderInline(line.slice(3), idx)}</h2>;
    }
    if (line.startsWith('- ') || line.startsWith('* ')) {
      return (
        <div key={idx} style={{ display: 'flex', gap: '0.5rem', marginLeft: '0.5rem', marginBottom: '0.2rem' }}>
          <span style={{ color: 'var(--accent-primary)' }}>•</span>
          <span>{renderInline(line.slice(2), idx)}</span>
        </div>
      );
    }
    if (line.trim() === '') {
      return <div key={idx} style={{ height: '0.4rem' }} />;
    }
    return <p key={idx} style={{ marginBottom: '0.35rem' }}>{renderInline(line, idx)}</p>;
  });
}

/** Kick-off message. The student has not typed anything yet, so the coach is told to start. */
const PRACTICE_OPENER =
  'The student has not answered yet. Begin the exercise: ask exactly one question or set one small '
  + 'task, then stop and wait for their reply.';

export default function AiCoachChatView({
  profile,
  chat,
  setChat,
  practiceRequest,
  onPracticeConsumed,
  onBackToRoadmap,
  onToggleMilestone,
}) {
  const [input, setInput] = useState('');
  const [markingComplete, setMarkingComplete] = useState(false);
  const messagesEndRef = useRef(null);
  // Practice requests already turned into a session. A click must produce exactly one opening
  // prompt, and StrictMode runs effects twice in development.
  const consumedRef = useRef(new Set());
  // What to re-send if the last request failed, so Retry does not duplicate the user's message.
  const lastRequestRef = useRef(null);

  const { sessionId, messages, loading, context } = chat;
  const completedIds = profile?.completedMilestones || [];
  const milestoneDone = context?.milestoneId ? completedIds.includes(context.milestoneId) : false;

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, loading]);

  /**
   * One round trip. Everything is keyed on `sessionId`: a reply that arrives after the user has
   * started a different exercise, or after their profile changed, belongs to a conversation that
   * no longer exists and is dropped rather than appended to the wrong thread.
   */
  const runSend = useCallback(async ({ targetSession, outgoingText, history, displayText }) => {
    lastRequestRef.current = { targetSession, outgoingText, history };

    setChat((prev) => {
      if (prev.sessionId !== targetSession) return prev;
      const withoutErrors = prev.messages.filter((m) => m.generatedBy !== 'error');
      return {
        ...prev,
        loading: true,
        messages: displayText
          ? [...withoutErrors, { role: 'user', content: displayText }]
          : withoutErrors,
      };
    });

    try {
      const res = await sendChatMessage(outgoingText, history);
      setChat((prev) => {
        if (prev.sessionId !== targetSession) return prev;
        return {
          ...prev,
          loading: false,
          messages: [...prev.messages, {
            role: 'assistant',
            content: res.reply,
            generatedBy: res.generatedBy,
            model: res.model,
          }],
        };
      });
    } catch (err) {
      setChat((prev) => {
        if (prev.sessionId !== targetSession) return prev;
        return {
          ...prev,
          loading: false,
          messages: [...prev.messages, {
            role: 'assistant',
            generatedBy: 'error',
            // No invented answer, no invented score: an outage is reported as an outage.
            content: `The AI coach could not be reached (${err.message}). Nothing was scored — press Retry when you are ready.`,
          }],
        };
      });
    }
  }, [setChat]);

  /** Opens a new session for a practice request, exactly once per click. */
  useEffect(() => {
    if (!practiceRequest || consumedRef.current.has(practiceRequest.requestId)) return;
    consumedRef.current.add(practiceRequest.requestId);

    const newSessionId = `practice-${practiceRequest.requestId}`;
    setChat({ sessionId: newSessionId, messages: [], loading: false, context: practiceRequest });
    onPracticeConsumed?.();

    const contextBlock = buildPracticeContextBlock(practiceRequest);
    runSend({
      targetSession: newSessionId,
      outgoingText: `${contextBlock}\n\n${PRACTICE_OPENER}`,
      history: [],
      displayText: null,
    });
  }, [practiceRequest, setChat, onPracticeConsumed, runSend]);

  const handleSend = (userText = null) => {
    const textToSend = typeof userText === 'string' ? userText : input;
    if (!textToSend.trim() || loading) return;

    // Only real exchanges are context; error notices are UI state, not conversation.
    const history = messages
      .filter((m) => m.generatedBy !== 'error' && (m.role === 'user' || m.role === 'assistant'))
      .slice(-6)
      .map((m) => ({ role: m.role, content: m.content }));

    const contextBlock = context ? buildPracticeContextBlock(context) : '';
    const outgoingText = contextBlock
      ? `${contextBlock}\n\nSTUDENT MESSAGE: ${textToSend.trim()}`
      : textToSend.trim();

    setInput('');
    runSend({
      targetSession: sessionId,
      outgoingText,
      history,
      displayText: textToSend.trim(),
    });
  };

  const handleRetry = () => {
    const last = lastRequestRef.current;
    if (!last || last.targetSession !== sessionId || loading) return;
    runSend({ ...last, displayText: null });
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleMarkComplete = async () => {
    if (!context?.milestoneId || !context?.roadmapId || markingComplete) return;
    setMarkingComplete(true);
    try {
      await onToggleMilestone?.(context.milestoneId, context.roadmapId, !milestoneDone);
    } finally {
      setMarkingComplete(false);
    }
  };

  // Suggestions follow the student's own field and stage rather than a fixed software list.
  const field = profile?.industry || 'my field';
  const topSkill = (profile?.skills || [])[0];
  const stage = profile?.yearOfStudy ? `a ${profile.yearOfStudy} student` : 'someone at my stage';

  const quickPrompts = [
    `🧭 What should I focus on in the next 3 months as ${stage} in ${field}?`,
    `🧩 Which skills is my profile missing compared with entry-level ${field} roles?`,
    `🤖 Give me an exercise on checking AI output in ${field}`,
    topSkill
      ? `📝 Rewrite a CV bullet about ${topSkill} using the STAR formula`
      : '📝 How do I write CV bullets using the STAR formula?',
    `🎤 Ask me a common interview question for ${field} internships`,
  ];

  const lastIsError = messages.length > 0 && messages[messages.length - 1].generatedBy === 'error';
  const label = practiceLabel(context);

  return (
    <div>
      <div className="page-header">
        <div className="page-header-text">
          <h1>Practice & coach</h1>
          <p>
            Work through exercises tied to your roadmap and get feedback on your answers
            {profile?.industry ? ` in ${profile.industry}` : ''}. Feedback is practice feedback —
            it is not a qualification, and it never marks anything complete for you.
          </p>
        </div>
      </div>

      {context && (
        <div className="glass-card" style={{ marginBottom: '1rem', padding: '0.9rem 1.1rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem', flexWrap: 'wrap' }}>
            <Dumbbell size={16} color="#a855f7" />
            <strong style={{ color: '#fff', fontSize: '0.9rem' }}>
              Practising: {label}
            </strong>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
              {context.source === 'roadmap' ? 'from your roadmap' : 'from a job posting'}
            </span>

            <div style={{ marginLeft: 'auto', display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
              {context.source === 'roadmap' && context.milestoneId && context.roadmapId && (
                <button
                  className="btn btn-secondary btn-sm"
                  onClick={handleMarkComplete}
                  disabled={markingComplete}
                >
                  {markingComplete ? <Loader2 className="animate-spin" size={13} /> : <CheckSquare size={13} />}
                  {milestoneDone ? 'Undo complete (self-reported)' : 'Mark milestone complete (self-reported)'}
                </button>
              )}
              <button className="btn btn-secondary btn-sm" onClick={onBackToRoadmap}>
                <ArrowLeft size={13} /> Back to roadmap
              </button>
            </div>
          </div>
          {context.source === 'job' && (
            <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '0.5rem' }}>
              This question came from a job posting, so it is not tied to a roadmap milestone and
              will not complete one. If it convinced you a milestone is done, tick it yourself on
              the roadmap.
            </p>
          )}
        </div>
      )}

      <div className="chat-wrapper">
        <div className="chat-messages">
          {messages.length === 0 && !loading && (
            <div className="chat-bubble chat-bubble-ai">
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.4rem', fontSize: '0.75rem', opacity: 0.8 }}>
                <Bot size={13} color="#818cf8" />
                <span style={{ color: '#818cf8', fontWeight: '700' }}>Skills coach</span>
              </div>
              <div style={{ lineHeight: '1.55' }}>
                {renderFormattedContent(
                  `Ask me anything about the skills you are building${profile?.industry ? ` in **${profile.industry}**` : ''}`
                  + ', or open a milestone from **Skills & roadmap** and press *Practice this skill* to be set an exercise.\n\n'
                  + 'Starting a new exercise begins a new conversation. Switching tabs keeps this one; reloading the page clears it.',
                )}
              </div>
            </div>
          )}

          {messages.map((msg, idx) => (
            <div
              key={idx}
              className={`chat-bubble ${msg.role === 'user' ? 'chat-bubble-user' : 'chat-bubble-ai'}`}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.4rem', fontSize: '0.75rem', opacity: 0.8 }}>
                {msg.role === 'user' ? (
                  <>
                    <User size={13} />
                    <span>You</span>
                  </>
                ) : (
                  <>
                    <Bot size={13} color="#818cf8" />
                    <span style={{ color: '#818cf8', fontWeight: '700' }}>Skills coach</span>
                    {msg.generatedBy === 'gemini' && (
                      <span className="ai-badge ai-badge-live">
                        <Sparkles size={10} /> {msg.model || 'Gemini'}
                      </span>
                    )}
                    {msg.generatedBy === 'offline' && (
                      <span className="ai-badge ai-badge-offline">AI unavailable</span>
                    )}
                    {msg.generatedBy === 'error' && (
                      <span className="ai-badge ai-badge-offline">Not delivered</span>
                    )}
                  </>
                )}
              </div>
              <div style={{ lineHeight: '1.55' }}>
                {renderFormattedContent(msg.content)}
              </div>
            </div>
          ))}

          {lastIsError && !loading && (
            <div style={{ display: 'flex', justifyContent: 'center', marginTop: '0.5rem' }}>
              <button className="btn btn-secondary btn-sm" onClick={handleRetry}>
                <RotateCcw size={13} /> Retry
              </button>
            </div>
          )}

          {loading && (
            <div className="chat-bubble chat-bubble-ai" style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
              <Loader2 className="animate-spin" size={16} color="#818cf8" />
              <span style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                {context ? 'Preparing your exercise…' : 'Reading your profile and writing an answer…'}
              </span>
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>

        {/* Quick prompts, hidden mid-exercise so a stray click cannot derail the session. */}
        {!context && (
          <div className="chat-suggestions">
            {quickPrompts.map((prompt, idx) => (
              <button
                key={idx}
                className="suggestion-chip"
                onClick={() => handleSend(prompt)}
                disabled={loading}
              >
                {prompt}
              </button>
            ))}
          </div>
        )}

        <div className="chat-input-row">
          <input
            type="text"
            className="chat-input"
            placeholder={context ? 'Write your answer…' : 'Ask about the skills you are building, your CV, interviews…'}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={loading}
          />
          <button
            className="btn btn-primary"
            onClick={() => handleSend()}
            disabled={loading || !input.trim()}
          >
            {loading ? <Loader2 className="animate-spin" size={16} /> : <Send size={16} />}
            Send
          </button>
        </div>
      </div>
    </div>
  );
}
