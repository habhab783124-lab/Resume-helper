export function useConversationScroll({ threshold = 80, getElement = () => null, onChange = () => {} } = {}) {
  let nearLatest = true
  let initial = true
  let lastIds = ''
  let hasNewMessages = false
  const setNew = value => { hasNewMessages = value; onChange(value) }
  function onScroll() {
    const el = getElement()
    if (!el) return
    nearLatest = el.scrollHeight - el.scrollTop - el.clientHeight <= threshold
    if (nearLatest) setNew(false)
  }
  function scrollToLatest(smooth = false) {
    const el = getElement()
    if (!el) return
    if (el.scrollTo) el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
    else el.scrollTop = el.scrollHeight
    nearLatest = true
    setNew(false)
  }
  function onMessagesChanged(messages = []) {
    const ids = messages.map(({ id, status }) => `${id}:${status}`).join('|')
    if (ids === lastIds) return
    const added = !!lastIds && ids !== lastIds
    lastIds = ids
    if (initial) { initial = false; scrollToLatest(); return }
    if (!added) return
    if (nearLatest) scrollToLatest(true)
    else setNew(true)
  }
  return { onScroll, onMessagesChanged, scrollToLatest, get hasNewMessages() { return hasNewMessages } }
}
