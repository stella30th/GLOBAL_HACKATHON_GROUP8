import React, { useState, useRef, useEffect } from 'react';
import { Send, Bot, User, Sparkles, Loader2 } from 'lucide-react';
import { sendChatMessage } from '../api';

export default function AiCoachChatView({ profile }) {
  const buildWelcome = (p) => {
    const name = p?.fullName || 'there';
    const title = p?.currentTitle || 'candidate';
    const field = p?.industry ? ` in **${p.industry}**` : '';
    const skills = (p?.skills || []).slice(0, 5).join(', ');
    return `Hi ${name}! I am your **AI Career Coach**.

` +
      `I have read your profile: **${title}**${field}` +
      `${skills ? `, with core skills in ${skills}` : ''}.

` +
      `Ask me anything about your career path, CV, interviews, compensation or opportunities abroad — ` +
      `I will answer for your actual field and profile.`;
  };

  const [messages, setMessages] = useState(() => [
    { role: 'assistant', content: buildWelcome(profile), generatedBy: 'welcome' },
  ]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const messagesEndRef = useRef(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  // Refresh the welcome only while it is still the sole message, so re-parsing a CV updates the
  // greeting but never wipes an ongoing conversation.
  useEffect(() => {
    setMessages((prev) =>
      prev.length <= 1 ? [{ role: 'assistant', content: buildWelcome(profile), generatedBy: 'welcome' }] : prev
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile?.fullName, profile?.currentTitle, profile?.industry, profile?.updatedAt]);

  useEffect(() => {
    scrollToBottom();
  }, [messages, loading]);

  const handleSend = async (userText = null) => {
    const textToSend = typeof userText === 'string' ? userText : input;
    if (!textToSend.trim() || loading) return;

    const userMessage = { role: 'user', content: textToSend.trim() };
    const updatedMessages = [...messages, userMessage];
    setMessages(updatedMessages);
    setInput('');
    setLoading(true);

    try {
      // Send only real exchanges as context; the welcome message is UI text, not conversation.
      const history = messages.filter((m) => m.generatedBy !== 'welcome').slice(-6);
      const res = await sendChatMessage(textToSend.trim(), history);
      setMessages([
        ...updatedMessages,
        { role: 'assistant', content: res.reply, generatedBy: res.generatedBy, model: res.model },
      ]);
    } catch {
      setMessages([
        ...updatedMessages,
        {
          role: 'assistant',
          content: 'Could not reach the AI Coach. The server may still be starting up — please try again in a few seconds.',
          generatedBy: 'error',
        },
      ]);
    } finally {
      setLoading(false);
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  // Suggestions follow the candidate's own field. The previous fixed list asked about EU Blue Cards
  // for developers and system-design interviews, which is the wrong conversation to offer someone
  // whose CV is about integrated circuits, nursing or accounting.
  const role = profile?.currentTitle || 'my role';
  const field = profile?.industry || 'my field';
  const topSkill = (profile?.skills || [])[0];
  const targetRole = (profile?.targetRoles || [])[0] || role;

  const quickPrompts = [
    `🧭 What should my next 6 months look like to land ${targetRole} roles?`,
    `🧩 Which skills is my profile missing compared with ${field} job requirements?`,
    `🎤 Give me 5 common interview questions for ${targetRole} roles`,
    topSkill
      ? `📝 Rewrite a CV bullet about ${topSkill} using the STAR formula with metrics`
      : '📝 How do I write CV bullets using the STAR formula with metrics?',
    `✈️ What are the visa options for working in ${field} abroad?`,
  ];

  // Markdown formatter
  const renderFormattedContent = (content) => {
    const lines = content.split('\n');
    return lines.map((line, idx) => {
      let processed = line;
      if (line.startsWith('### ')) {
        return <h3 key={idx} style={{ color: 'var(--text-primary)', margin: '0.6rem 0 0.3rem', fontSize: '1.05rem' }}>{line.replace('### ', '')}</h3>;
      }
      if (line.startsWith('## ')) {
        return <h2 key={idx} style={{ color: 'var(--text-primary)', margin: '0.8rem 0 0.4rem', fontSize: '1.2rem' }}>{line.replace('## ', '')}</h2>;
      }
      if (line.startsWith('- ') || line.startsWith('* ')) {
        return (
          <div key={idx} style={{ display: 'flex', gap: '0.5rem', marginLeft: '0.5rem', marginBottom: '0.2rem' }}>
            <span style={{ color: 'var(--accent-primary)' }}>•</span>
            <span dangerouslySetInnerHTML={{ __html: formatBold(line.substring(2)) }} />
          </div>
        );
      }
      if (line.trim() === '') {
        return <div key={idx} style={{ height: '0.4rem' }} />;
      }
      return (
        <p key={idx} style={{ marginBottom: '0.35rem' }} dangerouslySetInnerHTML={{ __html: formatBold(processed) }} />
      );
    });
  };

  const formatBold = (text) => {
    return text
      .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
      .replace(/\*(.*?)\*/g, '<em>$1</em>')
      .replace(/`([^`]+)`/g, '<code style="background:rgba(255,255,255,0.1);padding:2px 5px;border-radius:4px;font-family:monospace">$1</code>');
  };

  return (
    <div>
      <div className="page-header">
        <div className="page-header-text">
          <h1>AI Career Coach</h1>
          <p>
            Talk directly with an AI coach about your career path, CV, interviews and international
            opportunities — grounded in your own profile and field
            {profile?.industry ? ` (${profile.industry})` : ''}.
          </p>
        </div>
      </div>

      <div className="chat-wrapper">
        <div className="chat-messages">
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
                    <Bot size={13} style={{ color: 'var(--accent-soft)' }} />
                    <span style={{ color: 'var(--accent-soft)', fontWeight: '700' }}>AI Career Coach</span>
                    {msg.generatedBy === 'gemini' && (
                      <span className="ai-badge ai-badge-live">
                        <Sparkles size={10} /> {msg.model || 'Gemini'}
                      </span>
                    )}
                    {msg.generatedBy === 'offline' && (
                      <span className="ai-badge ai-badge-offline">AI unavailable</span>
                    )}
                  </>
                )}
              </div>
              <div style={{ lineHeight: '1.55' }}>
                {renderFormattedContent(msg.content)}
              </div>
            </div>
          ))}

          {loading && (
            <div className="chat-bubble chat-bubble-ai" style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
              <Sparkles className="ai-wave" size={16} style={{ color: 'var(--accent-soft)' }} />
              <span style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                AI is reviewing your profile and writing an answer
              </span>
              <span className="wave-dots" style={{ color: 'var(--accent-soft)' }}>
                <span /><span /><span />
              </span>
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>

        {/* Quick Prompts */}
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

        {/* Input Row */}
        <div className="chat-input-row">
          <input
            type="text"
            className="chat-input"
            placeholder="Ask about your career path, CV, interviews, compensation, working abroad…"
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
