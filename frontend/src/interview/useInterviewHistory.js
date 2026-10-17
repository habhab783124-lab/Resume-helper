import { ref } from 'vue'

export function useInterviewHistory({ api, scope }) {
  const items = ref([])
  const cursor = ref(null)
  const loading = ref(false)
  const error = ref('')
  let generation = 0
  let disposed = false
  const deletedIds = new Set()
  const currentScope = () => JSON.stringify(typeof scope === 'function' ? scope() : scope)

  async function loadMore() {
    if (loading.value || (items.value.length && !cursor.value)) return
    const ticket = ++generation
    const stamp = currentScope()
    loading.value = true; error.value = ''
    try {
      const page = await api.listHistory({ limit: 20, cursor: cursor.value })
      if (disposed || ticket !== generation || stamp !== currentScope()) return
      const known = new Set(items.value.map(item => item.id))
      items.value = [...items.value, ...(page.items || []).filter(item => !known.has(item.id) && !deletedIds.has(item.id))]
      cursor.value = page.nextCursor || null
    } catch (cause) {
      if (!disposed && ticket === generation && stamp === currentScope()) error.value = cause?.message || '历史记录加载失败'
    } finally {
      if (!disposed && ticket === generation) loading.value = false
    }
  }

  async function reload() {
    generation++; items.value = []; cursor.value = null
    await loadMore()
  }

  async function remove(id) {
    error.value = ''
    try {
      await api.deleteSession(id)
      deletedIds.add(id)
      items.value = items.value.filter(item => item.id !== id)
      return true
    } catch (cause) {
      error.value = cause?.message || '删除失败，请重试'
      return false
    }
  }

  function dispose() { disposed = true; generation++ }
  return { items, cursor, loading, error, loadMore, reload, remove, dispose }
}
