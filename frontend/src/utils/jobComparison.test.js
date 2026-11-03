import test from 'node:test'
import assert from 'node:assert/strict'
import {createComparisonSelection,COMPARISON_PREFERENCES} from './jobComparison.js'
const storage=()=>{const map=new Map();return {getItem:k=>map.get(k)??null,setItem:(k,v)=>map.set(k,v),removeItem:k=>map.delete(k)}}
const job=n=>({id:'job-'+n,title:'岗位 '+n,company:'示例'})
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
