export const COMPARISON_LIMIT = 4
export const COMPARISON_PREFERENCES = [
  {value:'balanced',label:'综合考虑'}, {value:'city',label:'城市优先'},
  {value:'salary',label:'薪资优先'}, {value:'skills',label:'技能贴合优先'}
]

/** Store only selection labels/IDs, scoped to an account and backend. Storage failure keeps this tab usable. */
export function createComparisonSelection(getScope, getStorage) {
  let scope=null,items=[]
  const key=value=>'resume-helper-job-comparison:'+value
  function read(){
    const next=getScope()
    if(next!==scope){
      scope=next;items=[]
      try{
        const saved=JSON.parse(getStorage()?.getItem(key(scope))||'[]')
        if(Array.isArray(saved))items=saved.filter(v=>v&&typeof v.id==='string'&&v.id.length>0&&v.id.length<=200)
          .filter((v,i,all)=>all.findIndex(x=>x.id===v.id)===i).slice(0,COMPARISON_LIMIT)
          .map(v=>({id:v.id,title:String(v.title||'岗位').slice(0,120),company:String(v.company||'').slice(0,120)}))
      }catch{/* A corrupt or blocked selection cache does not prevent comparison. */}
    }
    return items.map(v=>({...v}))
  }
  function save(next){items=next;try{getStorage()?.setItem(key(scope),JSON.stringify(items))}catch{}return read()}
  return {
    read,
    toggle(job){
      read()
      if(items.some(v=>v.id===job.id))return save(items.filter(v=>v.id!==job.id))
      if(items.length>=COMPARISON_LIMIT)throw Error('最多对比 4 个岗位，请先移除一个。')
      if(typeof job.id!=='string'||!job.id||job.id.length>200)throw Error('该岗位暂不能加入对比。')
      return save([...items,{id:job.id,title:String(job.title||'岗位').slice(0,120),company:String(job.company||'').slice(0,120)}])
    },
    remove(id){read();return save(items.filter(v=>v.id!==id))},
    clear(){read();return save([])},
    forget(){try{if(scope!==null)getStorage()?.removeItem(key(scope))}catch{}scope=null;items=[]}
  }
}
