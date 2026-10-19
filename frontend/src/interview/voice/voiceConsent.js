const CONSENT_KEY = 'career-studio-realtime-voice-consent-v1'

export function createVoiceConsent({ storage = globalThis.sessionStorage, key = CONSENT_KEY } = {}) {
  return {
    accepted() { try { return storage?.getItem?.(key) === 'accepted' } catch { return false } },
    accept() { try { storage?.setItem?.(key, 'accepted'); return true } catch { return false } },
  }
}
