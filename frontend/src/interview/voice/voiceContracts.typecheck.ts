import type { VoiceContext } from './voiceContracts.ts'

const context: VoiceContext = {
  scopeKey: 'local', sessionId: 'session', questionId: 'question', epoch: 1,
}
// @ts-expect-error epoch must remain numeric
const invalid: VoiceContext = { ...context, epoch: '1' }
void invalid
import type { VoiceClientMessage, VoiceServerMessage } from './voiceProtocol.ts'
// @ts-expect-error speech.start requires a questionId
const startMessage: VoiceClientMessage = { v: 1, type: 'speech.start', requestId: 'request' }
// @ts-expect-error cancel cannot contain an answer
const cancelMessage: VoiceClientMessage = { v: 1, type: 'speech.cancel', requestId: 'request', answer: 'text' }
void startMessage; void cancelMessage
function exhaustive(message: VoiceClientMessage): string {
  switch (message.type) {
    case 'session.hello': return message.ticket
    case 'speech.start': return message.questionId
    case 'audio.ack': return String(message.playedSamples)
    case 'speech.cancel': return message.requestId
    case 'session.ping': return message.nonce
    case 'session.pong': return message.nonce
    case 'session.close': return 'close'
    default: {
      const impossible: never = message
      return impossible
    }
  }
}
void exhaustive

// @ts-expect-error unrecognized wire messages are rejected before reaching the switch
const unknownWireMessage: VoiceServerMessage = { v: 1, type: 'unrecognized' }
void unknownWireMessage
