import {shallowRef} from 'vue'
import {getAuthUser,onAuthReset} from '../utils/authSession'
import {getLocalApiBaseUrl} from '../api/requestContext'
import {createComparisonSelection} from '../utils/jobComparison'

const selection=createComparisonSelection(
  ()=>JSON.stringify([getAuthUser()?.id||'',getLocalApiBaseUrl()||globalThis.location?.origin||'']),
  ()=>globalThis.sessionStorage
)
const selected=shallowRef([]),message=shallowRef('')
function refresh(){selected.value=selection.read()}
onAuthReset(()=>{selection.forget();selected.value=[];message.value=''})
globalThis.addEventListener?.('request-context-change',()=>{message.value='';refresh()})
globalThis.addEventListener?.('storage',event=>{if(event.key==='resume-helper-auth-session'||event.key===null){message.value='';refresh()}})
export function useJobComparison(){
  refresh()
  return {
    selected,message,
    toggle(job){try{selected.value=selection.toggle(job);message.value=''}catch(e){message.value=e.message}},
    remove(id){selected.value=selection.remove(id);message.value=''},
    clear(){selected.value=selection.clear();message.value=''},refresh
  }
}
