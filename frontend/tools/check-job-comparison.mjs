/** Real isolated backend + headless Edge acceptance. Uses synthetic accounts and no external APIs. */
import assert from 'node:assert/strict'
import {spawn} from 'node:child_process'
import {mkdtemp,mkdir,writeFile} from 'node:fs/promises'
import {createServer} from 'node:net'
import {tmpdir} from 'node:os'
import {resolve,join,dirname} from 'node:path'
import {fileURLToPath,pathToFileURL} from 'node:url'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..')
const work=await mkdtemp(join(tmpdir(),'job-comparison-'))
const artifacts=join(root,'verification/job-comparison-2026-11-03')
await mkdir(artifacts,{recursive:true})
const checks=[],children=[],errors=[]
let base,owner,other,socket,server,cdp,evaluate,intercept
const pause=ms=>new Promise(r=>setTimeout(r,ms))
async function until(fn,label,timeout=30000){const end=Date.now()+timeout;while(Date.now()<end){if(await fn())return;await pause(100)}throw Error('Timeout: '+label)}
async function check(label,fn){await fn();checks.push(label);console.log('PASS '+label)}
async function freePort(){const s=createServer();await new Promise(r=>s.listen(0,'127.0.0.1',r));const port=s.address().port;await new Promise(r=>s.close(r));return port}
function start(exe,args,env){const p=spawn(exe,args,{cwd:work,env,windowsHide:true,stdio:['ignore','pipe','pipe']});children.push(p);let output='';p.stdout.on('data',b=>output+=b);p.stderr.on('data',b=>output+=b);p.output=()=>output;return p}
async function stop(p){if(p&&p.exitCode===null){p.kill();await Promise.race([new Promise(r=>p.once('exit',r)),pause(3000)])}}
async function api(path,body,token=owner?.token,method=body?'POST':'GET',status=200){const response=await fetch(base+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(15000)});assert.equal(response.status,status,path);const envelope=await response.json();if(status===200)assert.equal(envelope.success,true,envelope.message);return envelope.data}
async function main(){
 const port=await freePort();base='http://127.0.0.1:'+port
 const env={...process.env,INTERVIEW_SKILL_DIR:join(root,'skills/interview'),AUTH_ENFORCE:'true',AUTH_TOKEN_SECRET:'comparison-acceptance-only-secret-0000000000000000000000000000',AI_API_KEY:'',AI_BASE_URL:'http://127.0.0.1:1/v1',TAVILY_API_KEY:'',TENCENT_JOBS_ENABLED:'false',NETEASE_JOBS_ENABLED:'false'}
 for(const part of ['AUTH','JOB','MATERIAL','RESUME','INTERVIEW','APPLICATION'])env[part+'_STORAGE_DIR']=join(work,part.toLowerCase())
 const args=['-jar',join(root,'backend/target/resume-helper-backend-0.1.0-SNAPSHOT.jar'),'--server.port='+port,'--server.address=127.0.0.1','--spring.web.resources.static-locations='+pathToFileURL(join(root,'frontend/dist')).href+'/','--app.interview.voice.enabled=false','--app.job.sources.mock-enabled=false','--app.job.sources.csv-enabled=false']
 async function boot(){server=start(process.env.JAVA_PATH||'C:/Program Files/Java/jdk-17/bin/java.exe',args,env);await until(async()=>{try{return (await fetch(base+'/index.html')).ok}catch{return false}},'backend',60000)}
 await boot()
 await check('comparison endpoint requires authentication',()=>api('/api/jobs/compare',{jobIds:['a','b']},'','POST',401))
 owner=await api('/api/auth/register',{email:'comparison-owner@example.test',password:'Comparison123!',displayName:'对比演示'},'')
 other=await api('/api/auth/register',{email:'comparison-other@example.test',password:'Comparison123!',displayName:'另一账号'},'')
 const cards=[]
 for(let n=0;n<5;n++){
   const card=await api('/api/jobs/import',{text:`岗位：Java开发${n+1}\n公司：示例公司${n+1}\n工作地点：${n===0?'上海':n===2?'未披露':'北京'}\n薪资：${n===0?'10K':n===2?'面议':'30K'}\n任职要求：本科，应届生；熟悉 Java 和 Redis。\n描述：本地测试示例，非真实招聘。`})
   cards.push(card);await api('/api/jobs/'+card.id+'/favorite',{})
 }
 const ids=cards.slice(0,2).map(j=>j.id)
 await check('no confirmed profile means no invented personal scores',async()=>{const r=await api('/api/jobs/compare',{jobIds:ids});assert.equal(r.profileAvailable,false);assert.ok(r.items.every(i=>i.score==null))})
 await api('/api/profile',{targetTitle:'Java开发',targetCities:['上海'],expectedSalary:'20K',skills:['Java'],education:'本科',yearsOfExperience:0,projects:['使用 Java 开发校园平台'],summary:'计算机专业在读'},owner.token,'PUT')
 await check('preference changes city versus salary ranking',async()=>{
   assert.equal((await api('/api/jobs/compare',{jobIds:ids,preference:'city'})).items[0].job.id,cards[0].id)
   assert.equal((await api('/api/jobs/compare',{jobIds:ids,preference:'salary'})).items[0].job.id,cards[1].id)
 })
 await check('other accounts cannot compare private imported jobs',async()=>{const r=await api('/api/jobs/compare',{jobIds:ids},other.token);assert.equal(r.items.length,0);assert.equal(r.unavailableIds.length,2)})
 await check('invalid selection count, duplicates and preference return 400',async()=>{
   await api('/api/jobs/compare',{jobIds:[ids[0]]},owner.token,'POST',400)
   await api('/api/jobs/compare',{jobIds:[ids[0],ids[0]]},owner.token,'POST',400)
   await api('/api/jobs/compare',{jobIds:cards.map(j=>j.id)},owner.token,'POST',400)
   await api('/api/jobs/compare',{jobIds:ids,preference:'invalid'},owner.token,'POST',400)
 })
 const debug=await freePort()
 start(process.env.CHROME_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',['--headless=new','--disable-gpu','--disable-extensions','--no-first-run','--no-default-browser-check','--remote-allow-origins=*','--remote-debugging-port='+debug,'--user-data-dir='+join(work,'browser'),'--window-size=1440,1100','about:blank'],env)
 let target;await until(async()=>{try{target=(await(await fetch('http://127.0.0.1:'+debug+'/json/list')).json()).find(p=>p.type==='page');return !!target}catch{return false}},'browser')
 socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{socket.onopen=r;socket.onerror=j})
 let next=0;const pending=new Map()
 socket.onmessage=({data})=>{const m=JSON.parse(data);if(m.id){const p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result)}}if(m.method==='Runtime.exceptionThrown')errors.push(m.params);if(m.method==='Fetch.requestPaused'&&intercept)intercept(m.params).catch(e=>errors.push(String(e)))}
 cdp=(method,params={})=>new Promise((resolve,reject)=>{const id=++next;const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout '+method))},20000);pending.set(id,{resolve:v=>{clearTimeout(timer);resolve(v)},reject:e=>{clearTimeout(timer);reject(e)}});socket.send(JSON.stringify({id,method,params}))})
 evaluate=async expression=>{const r=await cdp('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result?.value}
 await cdp('Page.enable');await cdp('Runtime.enable')
 const navigate=async path=>{await cdp('Page.navigate',{url:base+path});await until(()=>evaluate('document.readyState==="complete"'),'page ready')}
 const body=()=>evaluate('document.body.innerText')
 const button=label=>evaluate('(()=>{const b=[...document.querySelectorAll("button")].find(b=>b.textContent.trim()==='+JSON.stringify(label)+');if(!b||b.disabled)throw Error("Missing button: "+'+JSON.stringify(label)+');b.click()})()')
 const selectCard=card=>evaluate('(()=>{const card=[...document.querySelectorAll(".favorites-grid .workflow-card")].find(c=>c.querySelector("h2").textContent.trim()==='+JSON.stringify(card.title)+');const button=[...card.querySelectorAll("button")].find(b=>b.textContent.trim()==="加入对比");if(!button)throw Error("Missing comparison toggle");button.click()})()')
 const choose=async value=>{await evaluate('(()=>{const e=document.querySelector("select[aria-label=对比偏好]");e.value='+JSON.stringify(value)+';e.dispatchEvent(new Event("change",{bubbles:true}))})()')}
 const ready=()=>until(()=>evaluate('!!document.querySelector(".comparison-table")'),'comparison ready')
 const screenshot=async name=>{await evaluate('document.getAnimations().forEach(a=>a.finish())');const r=await cdp('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});await writeFile(join(artifacts,name),Buffer.from(r.data,'base64'))}
 await navigate('/login');await evaluate('localStorage.setItem("resume-helper-auth-session",'+JSON.stringify(JSON.stringify(owner))+')')
 await navigate('/jobs/compare')
 await check('empty comparison offers selection paths',async()=>{await until(async()=>(await body()).includes('先挑选至少两个岗位'),'empty state');assert.ok((await body()).includes('从收藏中选择'))})
 // More than a dozen distinct private fixtures exercise the actual matching endpoint and list UI.
 for(let n=0;n<25;n++)await api('/api/jobs/import',{text:`岗位：Java 搜索回归工程师${n}\n公司：数量测试${n}\n工作地点：上海\n薪资：15-22K\n任职要求：本科，3年以上；Java、Spring Boot。\n描述：自动验收数据，非真实招聘。`})
 await navigate('/jobs')
 await until(()=>evaluate('document.querySelectorAll(".job-card").length===24'),'first 24 job cards')
 await check('matching defaults to sixty without hidden experience or city restrictions',async()=>{
   const r=await api('/api/jobs/search',{profile:{targetTitle:'Java',skills:['Java']}})
   assert.equal(r.jobs.length,30);assert.equal(r.candidateCount,30)
   assert.ok((await body()).includes('返回 30 个岗位'));assert.ok((await body()).includes('经验：经验不限'))
   assert.ok((await body()).includes('本人导入（返回 30 条）'));assert.ok(!(await body()).includes('演示数据'))
 })
 await button('显示更多（已显示 24 / 30）')
 await check('show more reveals retained results beyond the first page',async()=>assert.equal(await evaluate('document.querySelectorAll(".job-card").length'),30))
 await screenshot('jobs-desktop.png')
 await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true})
 await check('matching count controls fit the mobile viewport',async()=>assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true))
 await screenshot('jobs-mobile.png');await cdp('Emulation.clearDeviceMetricsOverride')
 const searchRequests=[]
 intercept=async paused=>{
   const payload=JSON.parse(paused.request.postData)
   searchRequests.push({url:paused.request.url,payload})
   // Agent transport fixture: reuse the real rules search, never send test AI credentials outside this process.
   const search=payload.options?{...payload.options,profile:payload.profile}:payload
   const data=await api('/api/jobs/search',search)
   await cdp('Fetch.fulfillRequest',{requestId:paused.requestId,responseCode:200,responseHeaders:[{name:'Content-Type',value:'application/json'}],body:Buffer.from(JSON.stringify({success:true,data:{...data,trace:[]}})).toString('base64')})
 }
 await cdp('Fetch.enable',{patterns:[{urlPattern:'*api/jobs/search',requestStage:'Request'},{urlPattern:'*api/jobs/agent/run',requestStage:'Request'}]})
 await evaluate('(()=>{const e=document.querySelector(".jobs-search input");e.value="Java";e.dispatchEvent(new Event("input",{bubbles:true}))})()')
 await until(()=>searchRequests.length>0,'keyword search')
 await until(()=>evaluate('!document.querySelector(".job-dot.is-loading")'),'search completed')
 await evaluate('(()=>{const e=document.querySelector(".job-limit select");e.value="100";e.dispatchEvent(new Event("change",{bubbles:true}))})()')
 await until(()=>searchRequests.some(r=>r.payload.limit===100),'limit change')
 await until(()=>evaluate('!document.querySelector(".job-dot.is-loading")'),'count completed')
 await evaluate('[...document.querySelectorAll(".filter-select")].find(b=>b.textContent.includes("城市：")).click()')
 await until(()=>searchRequests.some(r=>r.payload.strictCity===true),'city filter')
 await until(()=>evaluate('!document.querySelector(".job-dot.is-loading")'),'city completed')
 await evaluate('[...document.querySelectorAll(".filter-select")].find(b=>b.textContent.includes("经验：")).click()')
 await until(()=>searchRequests.some(r=>r.payload.maxYears===1),'experience filter')
 await until(()=>evaluate('!document.querySelector(".job-dot.is-loading")'),'filters completed')
 await button('让智能体去找')
 await until(()=>searchRequests.some(r=>r.url.endsWith('/agent/run')),'agent request')
 await until(()=>evaluate('!document.querySelector(".job-dot.is-loading")'),'agent response')
 await check('search and agent preserve the same keyword, count and hard filters',async()=>{
   const regular=searchRequests.filter(r=>!r.payload.options).at(-1).payload
   const agent=searchRequests.find(r=>r.payload.options).payload.options
   for(const field of ['keyword','city','strictCity','maxYears','limit'])assert.equal(agent[field],regular[field],field)
   assert.equal(agent.keyword,'Java');assert.equal(agent.limit,100);assert.equal(agent.maxYears,1);assert.equal(agent.strictCity,true)
   assert.equal(await evaluate('document.querySelectorAll(".job-card").length'),1)
 })
 await cdp('Fetch.disable');intercept=null
 // A slow old request must not restore results for conditions the user has already changed.
 let oldRequest
 intercept=async paused=>{
   const payload=JSON.parse(paused.request.postData)
   if(payload.keyword==='old-condition'){oldRequest=paused;return}
   await cdp('Fetch.fulfillRequest',{requestId:paused.requestId,responseCode:200,responseHeaders:[{name:'Content-Type',value:'application/json'}],body:Buffer.from(JSON.stringify({success:true,data:{jobs:[],candidateCount:0,notes:['latest-condition'],sources:[]}})).toString('base64')})
 }
 await cdp('Fetch.enable',{patterns:[{urlPattern:'*api/jobs/search',requestStage:'Request'}]})
 const keywordInput=value=>evaluate('(()=>{const e=document.querySelector(".jobs-search input");e.value='+JSON.stringify(value)+';e.dispatchEvent(new Event("input",{bubbles:true}))})()')
 await keywordInput('old-condition');await until(()=>!!oldRequest,'held search');await keywordInput('new-condition')
 await until(async()=>(await body()).includes('latest-condition'),'new result')
 try{await cdp('Fetch.fulfillRequest',{requestId:oldRequest.requestId,responseCode:200,body:Buffer.from(JSON.stringify({success:true,data:{jobs:cards,notes:['stale-condition']}})).toString('base64')})}catch{/* An aborted request may already have been discarded by Chromium. */}
 await pause(100)
 await check('late matching responses cannot overwrite newer search conditions',async()=>{assert.ok(!(await body()).includes('stale-condition'));assert.equal(await evaluate('document.querySelectorAll(".job-card").length'),0)})
 await cdp('Fetch.disable');intercept=null
 await cdp('Network.enable');await cdp('Network.setBlockedURLs',{urls:['*api/jobs/*/match']})
 await button('＋ 粘贴岗位描述')
 await evaluate('(()=>{const e=document.querySelector(".job-panel-input");e.value="岗位：导入失败回归测试\\n公司：本地验证\\n描述：Java 开发测试数据";e.dispatchEvent(new Event("input",{bubbles:true}))})()')
 await button('导入并匹配')
 await until(async()=>(await body()).includes('本次匹配未完成'),'match failure after import')
 await check('a successful import remains usable when its follow-up matching fails',async()=>{
   assert.equal(await evaluate('document.querySelectorAll(".job-card").length'),1)
   assert.ok((await body()).includes('导入失败回归测试'))
 })
 await cdp('Network.setBlockedURLs',{urls:[]})
 await navigate('/applications?tab=favorites');await until(()=>evaluate('document.querySelectorAll(".favorites-grid .workflow-card").length===5'),'favorite cards')
 await selectCard(cards[0]);await selectCard(cards[1])
 await check('favorite cards add two choices and show a global comparison tray',async()=>{assert.equal(await evaluate('document.querySelectorAll(".comparison-tray-items button").length'),2)})
 await button('查看对比');await ready()
 await check('comparison table uses confirmed profile and unrepresented evidence wording',async()=>{const text=await body();assert.ok(text.includes('偏好参考分'));assert.ok(text.includes('未体现不代表不会'));assert.ok(text.includes('Redis'));assert.equal(await evaluate('document.querySelectorAll("thead th").length'),3)})
 let rowVariant='empty',deliveredVariant=''
 intercept=async paused=>{
   const variant=rowVariant,data=await api('/api/jobs/compare',JSON.parse(paused.request.postData))
   data.items=data.items.map(item=>({...item,job:{...item.job,place:'',salary:'未披露',experience:'',education:'',sourceName:'',sourceUrl:'',collectedAt:null,description:''},
     score:null,knownWeight:0,salaryMin:null,salaryMax:null,requiredSkills:[],evidencedSkills:[],unrepresentedSkills:[],notes:[],
     dimensions:item.dimensions.map(d=>({...d,points:null}))}))
   if(variant==='partial'){data.items[1].job.place='上海';data.items[1].score=0;data.items[1].dimensions[2].points=0;data.items[1].requiredSkills=['Java']}
   await cdp('Fetch.fulfillRequest',{requestId:paused.requestId,responseCode:200,responseHeaders:[{name:'Content-Type',value:'application/json'}],body:Buffer.from(JSON.stringify({success:true,data})).toString('base64')})
   deliveredVariant=variant
 }
 await cdp('Fetch.enable',{patterns:[{urlPattern:'*api/jobs/compare',requestStage:'Request'}]})
 await button('刷新确认资料与岗位快照');await until(()=>deliveredVariant==='empty','empty comparison response');await ready()
 await check('rows without information in either job are absent from the table',async()=>{
   assert.deepEqual(await evaluate('Array.from(document.querySelectorAll("tbody tr > th")).map(e=>e.textContent.trim())'),['下一步'])
 })
 await screenshot('comparison-empty-rows.png')
 rowVariant='partial';await button('刷新确认资料与岗位快照');await until(()=>deliveredVariant==='partial','partial comparison response');await ready()
 await check('one known job keeps its row and zero scores remain visible',async()=>{
   const labels=await evaluate('Array.from(document.querySelectorAll("tbody tr > th")).map(e=>e.textContent.trim())')
   assert.ok(labels.includes('工作地点'));assert.ok(!labels.includes('薪资原文'));assert.ok(!labels.includes('学历要求'))
   assert.ok(labels.some(label=>label.startsWith('偏好参考分')));assert.ok(labels.some(label=>label.startsWith('城市')))
   assert.equal(await evaluate('document.querySelector(".comparison-score-row td:nth-child(3) .comparison-score").textContent.trim()'),'0 / 100')
   assert.ok((await body()).includes('上海'));assert.ok((await body()).includes('未披露'))
 })
 await screenshot('comparison-partial-rows.png')
 await cdp('Fetch.disable');intercept=null;await button('刷新确认资料与岗位快照');await ready()
 await choose('city');await ready()
 await check('city preference shows the Shanghai opportunity first',async()=>{await until(()=>evaluate('document.querySelector("thead th:nth-child(2)").innerText.includes("Java开发1")'),'city order')})
 await choose('salary');await ready()
 await check('salary preference changes the visible ranking',async()=>{await until(()=>evaluate('document.querySelector("thead th:nth-child(2)").innerText.includes("Java开发2")'),'salary order')})
 await screenshot('comparison-desktop.png')
 await navigate('/jobs/compare');await ready()
 await check('direct history navigation and reload retain the selected jobs',async()=>assert.equal(await evaluate('document.querySelectorAll(".comparison-selection button").length'),2))
 await navigate('/applications?tab=favorites');await until(()=>evaluate('document.querySelectorAll(".favorites-grid .workflow-card").length===5'),'favorites again')
 await selectCard(cards[2]);await selectCard(cards[3]);await selectCard(cards[4])
 await check('fifth selection is refused without losing the existing four',async()=>{assert.equal(await evaluate('document.querySelectorAll(".comparison-tray-items button").length'),4);assert.ok((await body()).includes('最多对比 4 个岗位'))})
 await button('查看对比');await ready()
 await check('unknown salary and location remain explicitly unknown',async()=>{const text=await body();assert.ok(text.includes('岗位薪资未披露或无法换算'));assert.ok(text.includes('岗位地点未披露'))})
 await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true})
 await check('mobile page stays within viewport while table scrolls inside its container',async()=>{const m=await evaluate('({page:document.documentElement.scrollWidth,width:innerWidth,table:document.querySelector(".comparison-scroll").scrollWidth,container:document.querySelector(".comparison-scroll").clientWidth})');assert.ok(m.page<=m.width);assert.ok(m.table>m.container)})
 await screenshot('comparison-mobile.png');await cdp('Emulation.clearDeviceMetricsOverride')
 await cdp('Network.enable');await cdp('Network.setBlockedURLs',{urls:['*api/jobs/compare']});await choose('city')
 await check('failed comparison keeps selections and exposes retry',async()=>{await until(()=>evaluate('!!document.querySelector(".comparison-error")'),'error');assert.equal(await evaluate('document.querySelectorAll(".comparison-selection button").length'),4)})
 await cdp('Network.setBlockedURLs',{urls:[]});await button('重试');await ready()
 await check('retry restores the comparison table',async()=>assert.equal(await evaluate('document.querySelectorAll("thead th").length'),5))
 let completedCity=false
 intercept=async paused=>{
   const payload=JSON.parse(paused.request.postData),data=await api('/api/jobs/compare',payload)
   await pause(payload.preference==='city'?650:20)
   await cdp('Fetch.fulfillRequest',{requestId:paused.requestId,responseCode:200,responseHeaders:[{name:'Content-Type',value:'application/json'}],body:Buffer.from(JSON.stringify({success:true,data})).toString('base64')})
   if(payload.preference==='city')completedCity=true
 }
 await cdp('Fetch.enable',{patterns:[{urlPattern:'*api/jobs/compare',requestStage:'Request'}]})
 await choose('balanced');await ready();await choose('city');await pause(80);await choose('salary');await ready();await until(()=>completedCity,'late city response');await pause(100)
 await check('a late response cannot overwrite the latest preference',async()=>{assert.equal(await evaluate('document.querySelector("select[aria-label=对比偏好]").value'),'salary');assert.ok(await evaluate('document.querySelector("caption").innerText.includes("薪资优先")'))})
 await cdp('Fetch.disable');intercept=null
 await button('加入投递管理');await until(()=>evaluate('location.pathname==="/applications"&&!!document.querySelector(".application-form")'),'application handoff')
 await check('selected comparison job flows into a persisted application',async()=>assert.equal((await api('/api/applications')).total,1))
 await navigate('/jobs/compare');await ready();await stop(server);await boot();await navigate('/jobs/compare');await ready()
 await check('backend restart retains the job snapshots used for comparison',async()=>assert.equal(await evaluate('document.querySelectorAll("thead th").length'),5))
 await button('退出');await until(()=>evaluate('location.pathname==="/login"'),'logout')
 await evaluate('localStorage.setItem("resume-helper-auth-session",'+JSON.stringify(JSON.stringify(other))+')');await navigate('/jobs/compare')
 await check('logout and another account cannot inherit private comparison choices',async()=>{await until(async()=>(await body()).includes('先挑选至少两个岗位'),'other account');assert.equal(await evaluate('document.querySelectorAll(".comparison-selection button").length'),0)})
 if(process.env.COMPARISON_DEMO_URL){
   const demo=new URL(process.env.COMPARISON_DEMO_URL);assert.equal(demo.hostname,'127.0.0.1')
   await cdp('Page.navigate',{url:demo.href});await ready()
   await check('local demo entry signs into synthetic data and opens three comparison columns',async()=>{
     assert.equal(await evaluate('location.pathname'),'/jobs/compare')
     assert.equal(await evaluate('document.querySelectorAll("thead th").length'),4)
   })
   await screenshot('comparison-live-demo.png')
 }
 assert.deepEqual(errors,[])
 console.log('PASS '+checks.length+' job comparison checks')
}
try{await main();await writeFile(join(artifacts,'result.json'),JSON.stringify({passed:checks.length,checks,errors},null,2))}
catch(e){console.error(e);await writeFile(join(artifacts,'failure.txt'),String(e)+'\n'+(server?.output()||''));if(evaluate)try{await writeFile(join(artifacts,'failure-page.txt'),await evaluate('document.body.innerText'))}catch{}process.exitCode=1}
finally{socket?.close();for(const p of children.reverse())await stop(p)}
