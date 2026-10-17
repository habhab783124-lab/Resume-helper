export function shouldSend(event) {
  return event?.key === 'Enter' && !event.isComposing && (event.ctrlKey || event.metaKey)
}

export function insertIndent(text, start, end = start) {
  const value = String(text ?? '')
  const from = Math.max(0, Math.min(value.length, start ?? value.length))
  const to = Math.max(from, Math.min(value.length, end ?? from))
  const spaces = '    '
  return { text: `${value.slice(0, from)}${spaces}${value.slice(to)}`, start: from + spaces.length, end: from + spaces.length }
}
