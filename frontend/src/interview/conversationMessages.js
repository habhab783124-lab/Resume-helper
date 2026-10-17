export function toConversationMessages(session, pendingSubmission = null, phase = '') {
  if (!session || !Array.isArray(session.turns)) return []
  const messages = []
  for (const turn of session.turns) {
    const kind = session.questionMetadata?.[turn.questionId]?.kind || 'MAIN'
    if (turn.question) messages.push({ id: `${turn.questionId}:question`, role: 'assistant', questionId: turn.questionId, text: turn.question, status: 'complete', kind })
    if (turn.answer != null) messages.push({ id: `${turn.questionId}:answer`, role: 'user', questionId: turn.questionId, text: turn.answer, status: 'complete', kind })
    else if (pendingSubmission?.questionId === turn.questionId && pendingSubmission.answer) messages.push({ id: `${turn.questionId}:answer`, role: 'user', questionId: turn.questionId, text: pendingSubmission.answer, status: 'unconfirmed', kind })
  }
  const current = session.status === 'ACTIVE' ? session.currentQuestion : null
  if (current?.question && !session.turns.some(turn => turn.questionId === current.questionId && turn.question === current.question)) {
    const kind = session.questionMetadata?.[current.questionId]?.kind || 'MAIN'
    messages.push({ id: `${current.questionId}:question`, role: 'assistant', questionId: current.questionId, text: current.question, status: 'complete', kind })
  } else if (session.status === 'ACTIVE' && !current && ['GENERATING', 'RETRY_WAIT'].includes(phase)) {
    messages.push({ id: `generation:${session.sessionId}`, role: 'assistant', questionId: '', text: phase === 'RETRY_WAIT' ? '连接暂时中断，正在重试生成下一题…' : '正在准备下一题…', status: 'pending', kind: 'STATUS' })
  }
  return messages
}
