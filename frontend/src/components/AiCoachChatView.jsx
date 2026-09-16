import React, { useState, useRef, useEffect } from 'react';
import { Send, Bot, User, Sparkles, Loader2 } from 'lucide-react';
import { sendChatMessage } from '../api';

export default function AiCoachChatView({ profile, isConnected }) {
  const buildWelcome = (p) => {
    const name = p?.fullName || 'bạn';
    const title = p?.currentTitle || 'ứng viên';
    const field = p?.industry ? ` trong lĩnh vực **${p.industry}**` : '';
    const skills = (p?.skills || []).slice(0, 5).join(', ');
    return `Chào ${name}! Mình là **AI Career Coach** của bạn.

` +
      `Mình đang đọc hồ sơ của bạn: **${title}**${field}` +
      `${skills ? `, với các kỹ năng chính: ${skills}` : ''}.

` +
      `Hãy hỏi mình bất cứ điều gì về lộ trình nghề nghiệp, CV, phỏng vấn, lương thưởng hoặc cơ hội ở nước ngoài — ` +
      `mình sẽ trả lời dựa trên đúng ngành và hồ sơ của bạn.`;
  };

  const [messages, setMessages] = useState(() => [
    { role: 'assistant', content: buildWelcome(profile), generatedBy: 'welcome' },
  ]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [lastEngine, setLastEngine] = useState(null);
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
      setLastEngine({ generatedBy: res.generatedBy, model: res.model });
      setMessages([
        ...updatedMessages,
        { role: 'assistant', content: res.reply, generatedBy: res.generatedBy, model: res.model },
      ]);
    } catch (err) {
      setMessages([
        ...updatedMessages,
        {
          role: 'assistant',
          content: 'Không kết nối được tới AI Coach. Máy chủ có thể đang khởi động lại — vui lòng thử lại sau ít giây.',
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
  const role = profile?.currentTitle || 'vị trí của tôi';
  const field = profile?.industry || 'ngành của tôi';
  const topSkill = (profile?.skills || [])[0];
  const targetRole = (profile?.targetRoles || [])[0] || role;

  const quickPrompts = [
    `🧭 Lộ trình 6 tháng tới để tôi ứng tuyển vị trí ${targetRole}?`,
    `🧩 Hồ sơ của tôi còn thiếu kỹ năng gì so với yêu cầu tuyển dụng ${field}?`,
    `🎤 Cho tôi 5 câu hỏi phỏng vấn thường gặp cho ${targetRole}`,
    topSkill
      ? `📝 Viết lại một gạch đầu dòng CV về ${topSkill} theo công thức STAR có số liệu`
      : '📝 Cách viết gạch đầu dòng CV theo công thức STAR có số liệu',
    `✈️ Cơ hội và điều kiện visa để làm ${field} ở nước ngoài?`,
  ];

  // Markdown formatter
  const renderFormattedContent = (content) => {
    const lines = content.split('\n');
    return lines.map((line, idx) => {
      let processed = line;
      if (line.startsWith('### ')) {
        return <h3 key={idx} style={{ color: '#fff', margin: '0.6rem 0 0.3rem', fontSize: '1.05rem' }}>{line.replace('### ', '')}</h3>;
      }
      if (line.startsWith('## ')) {
        return <h2 key={idx} style={{ color: '#fff', margin: '0.8rem 0 0.4rem', fontSize: '1.2rem' }}>{line.replace('## ', '')}</h2>;
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
            Trao đổi trực tiếp với AI về lộ trình nghề nghiệp, CV, phỏng vấn và cơ hội quốc tế —
            dựa trên đúng hồ sơ và lĩnh vực của bạn
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
                    <Bot size={13} color="#818cf8" />
                    <span style={{ color: '#818cf8', fontWeight: '700' }}>AI Career Coach</span>
                    {msg.generatedBy === 'gemini' && (
                      <span className="ai-badge ai-badge-live">
                        <Sparkles size={10} /> {msg.model || 'Gemini'}
                      </span>
                    )}
                    {msg.generatedBy === 'offline' && (
                      <span className="ai-badge ai-badge-offline">AI tạm không khả dụng</span>
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
              <Loader2 className="animate-spin" size={16} color="#818cf8" />
              <span style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>AI đang phân tích hồ sơ của bạn và soạn câu trả lời…</span>
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
            placeholder="Hỏi AI về lộ trình nghề nghiệp, CV, phỏng vấn, lương thưởng, cơ hội nước ngoài…"
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
