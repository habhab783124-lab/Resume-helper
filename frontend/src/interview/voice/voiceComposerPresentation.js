const ACTIVE = new Set(['STARTING', 'SPEAKING', 'LISTENING', 'TRANSCRIBING', 'REVIEW', 'SUBMITTING', 'WAITING_QUESTION'])

export function voiceComposerPresentation(state = {}, speechAvailable = true) {
  const phase = state.phase || 'OFF'
  if (!speechAvailable) return { action: 'manual', label: '语音输入', disabled: false, active: false }
  if (phase === 'REVIEW') return { action: 'pause', label: '暂停语音并保留回答', disabled: false, active: true }
  if (phase === 'STARTING') return { action: 'pause', label: '取消开启语音', disabled: false, active: true }
  if (phase === 'SUBMITTING' || phase === 'WAITING_QUESTION')
    return { action: 'pause', label: '语音处理中', disabled: true, active: true }
  if (ACTIVE.has(phase)) return { action: 'pause', label: '暂停语音输入', disabled: false, active: true }
  return { action: 'start', label: phase === 'PAUSED' ? '继续语音输入' : '开始语音输入', disabled: false, active: false }
}
