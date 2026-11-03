import test from 'node:test'
import assert from 'node:assert/strict'
import {createComparisonSelection,COMPARISON_PREFERENCES,getComparisonRowVisibility} from './jobComparison.js'
const storage=()=>{const map=new Map();return {getItem:k=>map.get(k)??null,setItem:(k,v)=>map.set(k,v),removeItem:k=>map.delete(k)}}
const job=n=>({id:'job-'+n,title:'岗位 '+n,company:'示例'})
const blankComparison=n=>({job:{...job(n),place:'',salary:'未披露',experience:null,education:' ',sourceName:'',sourceUrl:'',collectedAt:null,description:''},
  score:null,salaryMin:null,salaryMax:null,requiredSkills:[],evidencedSkills:[],unrepresentedSkills:[],notes:[],
  dimensions:[{label:'技能匹配',points:null},{label:'城市',points:null}]})

test('comparison hides information rows missing from every job',()=>{
  const visibility=getComparisonRowVisibility([blankComparison(1),blankComparison(2)])
  assert.deepEqual(visibility,{fields:[],dimensions:[],score:false,requiredSkills:false,
    evidencedSkills:false,unrepresentedSkills:false,source:false,details:false})
  assert.deepEqual(getComparisonRowVisibility(),visibility)
})

test('comparison retains a row when just one job supplies its value',()=>{
  const first=blankComparison(1),second=blankComparison(2)
  Object.assign(second.job,{place:'上海',salary:'20K',education:'本科',sourceUrl:'https://example.test/job',description:'岗位详情'})
  Object.assign(second,{requiredSkills:['Java'],evidencedSkills:['Java'],unrepresentedSkills:['Redis'],score:50})
  second.dimensions[1].points=10
  const visibility=getComparisonRowVisibility([first,second])
  assert.deepEqual(visibility.fields,['place','salary','education'])
  assert.deepEqual(visibility.dimensions,[1])
  for(const key of ['score','requiredSkills','evidencedSkills','unrepresentedSkills','source','details'])assert.equal(visibility[key],true,key)
})

test('comparison checks every selected job, including a fourth job',()=>{
  const items=[1,2,3,4].map(blankComparison)
  items[3].job.experience='应届生'
  assert.deepEqual(getComparisonRowVisibility(items).fields,['experience'])
  assert.deepEqual(getComparisonRowVisibility(items.slice(0,3)).fields,[])
})

test('zero scores, explicit unlimited requirements and normalized salary are meaningful',()=>{
  const item=blankComparison(1)
  Object.assign(item.job,{experience:'不限',education:'不限'})
  Object.assign(item,{score:0,salaryMin:10,salaryMax:15})
  item.dimensions[0].points=0
  const visibility=getComparisonRowVisibility([blankComparison(2),item])
  assert.deepEqual(visibility.fields,['salary','experience','education'])
  assert.deepEqual(visibility.dimensions,[0])
  assert.equal(visibility.score,true)
})

test('placeholder text, whitespace and empty skill values do not keep a row visible',()=>{
  const item=blankComparison(1)
  Object.assign(item.job,{place:' 未披露 ',salary:'面议',experience:'—',education:'未知'})
  item.requiredSkills=[' ','未提供']
  assert.deepEqual(getComparisonRowVisibility([item,blankComparison(2)]).fields,[])
  assert.equal(getComparisonRowVisibility([item]).requiredSkills,false)
})
test('comparison selection deduplicates, toggles and refuses a fifth without dropping previous choices',()=>{
  const store=storage(),s=createComparisonSelection(()=> 'alice|local',()=>store)
  for(let n=1;n<=4;n++)s.toggle(job(n))
  assert.throws(()=>s.toggle(job(5)),/最多/);assert.equal(s.read().length,4)
  s.toggle(job(2));assert.deepEqual(s.read().map(v=>v.id),['job-1','job-3','job-4'])
  assert.equal(s.toggle(job(5)).length,4)
})
test('selection survives refresh but is isolated by both account and backend',()=>{
  let scope='alice|a';const store=storage(),s=createComparisonSelection(()=>scope,()=>store)
  s.toggle(job(1));assert.equal(createComparisonSelection(()=>scope,()=>store).read().length,1)
  scope='bob|a';assert.deepEqual(s.read(),[]);s.toggle(job(2))
  scope='alice|b';assert.deepEqual(s.read(),[])
  scope='alice|a';assert.deepEqual(s.read().map(v=>v.id),['job-1'])
})
test('blocked storage retains current selection and switching scope clears old memory',()=>{
  let scope='alice';const blocked={getItem(){throw Error()},setItem(){throw Error()},removeItem(){throw Error()}}
  const s=createComparisonSelection(()=>scope,()=>blocked);s.toggle(job(1));assert.equal(s.read().length,1)
  scope='bob';assert.equal(s.read().length,0);s.toggle(job(2));s.forget();assert.deepEqual(s.read(),[])
})
test('forget removes persisted selection on logout and corrupt caches are ignored',()=>{
  const store=storage(),s=createComparisonSelection(()=> 'alice',()=>store);s.toggle(job(1));s.forget()
  assert.deepEqual(createComparisonSelection(()=> 'alice',()=>store).read(),[])
  store.setItem('resume-helper-job-comparison:alice','{broken');assert.deepEqual(s.read(),[])
})
test('untrusted cache content is bounded and deduplicated',()=>{
  const store=storage();store.setItem('resume-helper-job-comparison:alice',JSON.stringify([null,job(1),job(1),{id:2},job(2),job(3),job(4),job(5)]))
  const s=createComparisonSelection(()=> 'alice',()=>store);assert.equal(s.read().length,4)
  assert.equal(s.remove('job-2').length,3);assert.equal(s.clear().length,0)
  assert.deepEqual(COMPARISON_PREFERENCES.map(p=>p.value),['balanced','city','salary','skills'])
})
