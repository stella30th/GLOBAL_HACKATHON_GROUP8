import { useEffect, useRef, useState } from 'react';
import { X, Send, Loader2, AlertTriangle } from 'lucide-react';
import { sendChatMessage } from '../api';

const SUGGESTIONS = [
  'Why is this the first phase?',
  'How do I start the first activity?',
  'Why was this skill judged a gap?',
  'What should I do if I get stuck?',
];

/**
 * The "ask about your path" panel.
 *
 * <p>A slide-over rather than a page, because that is what it is for: reading an explanation of
 * something on the page behind it. Making chat a destination of its own was what let it compete
 * with the roadmap for attention when it is a support tool.
 *
 * <p>It never writes to the plan. If the model's answer suggests a change, the student makes it in
 * the input panel and regenerates, which produces a new version with its own provenance rather
 * than an untracked edit made inside a conversation.
 */
export default function CoachChatPanel({ open, onClose, chat, setChat, hasPlan }) {
  const [draft, setDraft] = useState('');
  const scrollRef = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => {
    if (open) {
      inputRef.current?.focus();
    }
  }, [open]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [chat.messages, chat.loading]);

  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const send = async (text) => {
    const question = (text ?? draft).trim();
    if (!question || chat.loading) return;

    const history = chat.messages.map((m) => ({ role: m.role, content: m.content }));
    setDraft('');
    setChat((current) => ({
      ...current,
      messages: [...current.messages, { role: 'user', content: question }],
      loading: true,
      error: null,
    }));

    try {
      const response = await sendChatMessage(question, history);
      setChat((current) => ({
        ...current,
        messages: [...current.messages,
          { role: 'assistant', content: response.reply, model: response.model }],
        loading: false,
      }));
    } catch (err) {
      // No scripted reply stands in for a failure. In a coaching panel a canned answer reads as
      // advice, and the student has no way to tell it apart from the real thing.
      setChat((current) => ({
        ...current,
        loading: false,
        error: { message: err.message, detail: err.detail, question },
      }));
    }
  };

  if (!open) return null;

  return (
    <>
      <div className="panel-scrim" onClick={onClose} aria-hidden="true" />
      <aside className="chat-panel" role="dialog" aria-label="Ask about your path">
        <header className="chat-panel-header">
          <div>
            <h3>Ask about your path</h3>
            <p className="muted-note">
              Explains the analysis and the phases. It cannot change the plan or tick anything off.
            </p>
          </div>
          <button type="button" className="modal-close-btn" onClick={onClose} aria-label="Close">
            <X size={18} />
          </button>
        </header>

        <div className="chat-messages" ref={scrollRef}>
          {chat.messages.length === 0 && (
            <div className="chat-intro">
              <p>
                {hasPlan
                  ? 'Ask about any part of your path — why a skill is a gap, why a phase comes when it does, or how to get started on an activity.'
                  : 'Build a path first and this can explain your specific phases. Until then, ask about your profile or the role you are aiming at.'}
              </p>
              <div className="chat-suggestions">
                {SUGGESTIONS.map((suggestion) => (
                  <button
                    key={suggestion}
                    type="button"
                    className="suggestion-chip"
                    onClick={() => send(suggestion)}
                  >
                    {suggestion}
                  </button>
                ))}
              </div>
            </div>
          )}

          {chat.messages.map((message, index) => (
            <div
              key={index}
              className={`chat-bubble ${message.role === 'user' ? 'chat-bubble-user' : 'chat-bubble-ai'}`}
            >
              {message.content}
              {message.role === 'assistant' && message.model && (
                <span className="chat-model-tag">{message.model}</span>
              )}
            </div>
          ))}

          {chat.loading && (
            <div className="chat-bubble chat-bubble-ai chat-thinking">
              <Loader2 className="animate-spin" size={14} /> Thinking…
            </div>
          )}

          {chat.error && (
            <div className="inline-error">
              <AlertTriangle size={15} />
              <div>
                <p>{chat.error.message}</p>
                {chat.error.detail && (
                  <p className="inline-error-detail">{chat.error.detail}</p>
                )}
                <button
                  type="button"
                  className="link-btn"
                  onClick={() => send(chat.error.question)}
                >
                  Ask again
                </button>
              </div>
            </div>
          )}
        </div>

        <div className="chat-input-row">
          <input
            ref={inputRef}
            className="chat-input"
            placeholder="Ask a question…"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                send();
              }
            }}
            disabled={chat.loading}
          />
          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={() => send()}
            disabled={chat.loading || !draft.trim()}
            aria-label="Send"
          >
            <Send size={15} />
          </button>
        </div>
      </aside>
    </>
  );
}
