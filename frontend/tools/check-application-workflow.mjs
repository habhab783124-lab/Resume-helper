/**
 * Isolated application acceptance. Requires Java 17, built backend + frontend, Edge/Chrome.
 * No real credentials, external recruitment APIs or model services are used.
 */
import assert from 'node:assert/strict'
import {spawn} from 'node:child_process'
import {mkdtemp,mkdir,writeFile,readFile} from 'node:fs/promises'
import {createServer} from 'node:net'
import {tmpdir} from 'node:os'
import {resolve,join,dirname} from 'node:path'
import {fileURLToPath} from 'node:url'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..')
const work=await mkdtemp(join(tmpdir(),'application-acceptance-'))
const artifacts=join(root,'verification/implementation-2026-11-03/browser')
await mkdir(artifacts,{recursive:true})
const checks=[],children=[],browserErrors=[]
let server,socket,base,owner,other,cdp,evaluate
const pause=ms=>new Promise(r=>setTimeout(r,ms))
async function until(fn,name,timeout=45000){const end=Date.now()+timeout;while(Date.now()<end){if(await fn())return;await pause(120)}throw Error('Timeout: '+name)}
async function check(name,fn){await fn();checks.push(name);console.log('PASS '+name)}
async function freePort(){const s=createServer();await new Promise(r=>s.listen(0,'127.0.0.1',r));const port=s.address().port;await new Promise(r=>s.close(r));return port}
function start(exe,args,env){const child=spawn(exe,args,{cwd:work,env,windowsHide:true,stdio:['ignore','pipe','pipe']});children.push(child);let output='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);child.on('error',e=>output+=e);child.output=()=>output;return child}
async function stop(child){if(child&&child.exitCode===null){child.kill();await Promise.race([new Promise(r=>child.once('exit',r)),pause(5000)])}}
async function call(path,body,token=owner?.token,method=body?'POST':'GET',expected=200){
 const reply=await fetch(base+path,{method,signal:AbortSignal.timeout(15000),headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})})
 assert.equal(reply.status,expected,path);const envelope=await reply.json();if(expected===200)assert.equal(envelope.success,true,path+': '+envelope.message);return envelope.data
}
async function main(){
 const port=await freePort();base='http://127.0.0.1:'+port
 const env={...process.env,INTERVIEW_SKILL_DIR:join(root,'skills/interview'),AUTH_ENFORCE:'true',AUTH_TOKEN_SECRET:'application-acceptance-only-secret-000000000000000000000000000000',
 AUTH_STORAGE_DIR:join(work,'auth'),JOB_STORAGE_DIR:join(work,'jobs'),MATERIAL_STORAGE_DIR:join(work,'materials'),RESUME_STORAGE_DIR:join(work,'resumes'),INTERVIEW_STORAGE_DIR:join(work,'interviews'),
 AI_API_KEY:'',AI_BASE_URL:'http://127.0.0.1:1/v1',TAVILY_API_KEY:'',TENCENT_JOBS_ENABLED:'false',NETEASE_JOBS_ENABLED:'false'}
 const args=['-jar',join(root,'backend/target/resume-helper-backend-0.1.0-SNAPSHOT.jar'),'--server.port='+port,'--server.address=127.0.0.1','--spring.web.resources.static-locations=file:'+join(root,'frontend/dist').replaceAll('\\','/')+'/',
 '--app.interview.voice.enabled=false','--app.job.sources.mock-enabled=false','--app.job.sources.csv-enabled=false','--app.application.storage-dir='+join(work,'applications')]
 async function boot(){server=start(process.env.JAVA_PATH||'C:/Program Files/Java/jdk-17/bin/java.exe',args,env);await until(async()=>{try{return (await fetch(base+'/index.html')).ok}catch{return false}},'server',60000)}
 await boot()
 await check('applications require authentication',()=>call('/api/applications',null,'','GET',401))
 owner=await call('/api/auth/register',{email:'owner-'+Date.now()+'@example.test',password:'Acceptance123!',displayName:'投递验收'},'')
 other=await call('/api/auth/register',{email:'other-'+Date.now()+'@example.test',password:'Acceptance123!',displayName:'隔离验收'},'')
 const job=await call('/api/jobs/import',{text:'岗位：Java后端实习生\n公司：测试公司（总部北京）\n工作地点：上海\n薪资：300元/天\n任职要求：熟悉Java、Redis、MySQL'})
 await check('explicit workplace wins over headquarters',async()=>assert.equal(job.city,'上海'))
 await call('/api/jobs/'+job.id+'/favorite',{})
 const resume=await call('/api/resume/versions',{name:'投递验收简历',targetTitle:'Java开发',jd:'Redis',draft:{headline:'Java开发',summary:'有项目实践的应届生',fullName:'测试同学',skills:['Java'],projects:[{title:'校园平台',meta:'课程项目',bullets:['实现登录与缓存']}],contacts:[],educations:[],experiences:[],keywords:[],highlights:[],templateId:'technical'}})
 await check('favorites are owner scoped',async()=>{assert.equal((await call('/api/jobs/favorites')).jobs.length,1);assert.equal((await call('/api/jobs/favorites',null,other.token)).jobs.length,0)})
 const debug=await freePort()
 start(process.env.CHROME_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',['--headless=new','--disable-gpu','--disable-extensions','--no-first-run','--no-default-browser-check','--remote-allow-origins=*','--remote-debugging-port='+debug,'--user-data-dir='+join(work,'browser'),'--window-size=1440,1000','about:blank'],env)
 let target
 await until(async()=>{try{target=(await (await fetch('http://127.0.0.1:'+debug+'/json/list')).json()).find(x=>x.type==='page');return !!target}catch{return false}},'browser')
 socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{socket.onopen=r;socket.onerror=j})
 let next=0;const pending=new Map()
 socket.onmessage=({data})=>{const message=JSON.parse(data);if(message.id){const item=pending.get(message.id);if(item){pending.delete(message.id);message.error?item.reject(Error(JSON.stringify(message.error))):item.resolve(message.result)}}if(message.method==='Runtime.exceptionThrown')browserErrors.push(message.params)}
 cdp=(method,params={})=>new Promise((resolve,reject)=>{const id=++next;const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout '+method))},15000);pending.set(id,{resolve:v=>{clearTimeout(timer);resolve(v)},reject:e=>{clearTimeout(timer);reject(e)}});socket.send(JSON.stringify({id,method,params}))})
 evaluate=async expression=>{const result=await cdp('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));return result.result?.value}
 await cdp('Page.enable');await cdp('Runtime.enable')
 const navigate=async path=>{await cdp('Page.navigate',{url:base+path});await until(()=>evaluate('document.readyState==="complete"'),'page ready')}
 const text=()=>evaluate('document.body.innerText')
 const button=label=>evaluate('(()=>{const b=[...document.querySelectorAll("button")].find(b=>b.textContent.trim()==='+JSON.stringify(label)+');if(!b||b.disabled)throw Error("Unavailable button");b.click()})()')
 const fill=(selector,value)=>evaluate('(()=>{const e=document.querySelector('+JSON.stringify(selector)+');if(!e)throw Error("Missing input");e.value='+JSON.stringify(value)+';e.dispatchEvent(new Event("input",{bubbles:true}));e.dispatchEvent(new Event("change",{bubbles:true}))})()')
 const shot=async name=>{const r=await cdp('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});await writeFile(join(artifacts,name),Buffer.from(r.data,'base64'))}
 await navigate('/login')
 await evaluate('localStorage.setItem("resume-helper-auth-session",'+JSON.stringify(JSON.stringify(owner))+')')
 await navigate('/applications')
 await until(async()=>(await text()).includes('投递记录')&&!(await text()).includes('正在加载求职记录'),'application page')
 await button('我的收藏')
 await until(()=>evaluate('document.querySelectorAll(".favorites-grid .workflow-card").length===1'),'favorite card')
 await button('加入投递管理')
 await until(()=>evaluate('!!document.querySelector(".application-form")'),'created application')
 let app=(await call('/api/applications')).items[0]
 await check('UI creates one application and repeated create is idempotent',async()=>{const repeated=await call('/api/applications',{jobId:job.id});assert.equal(app.id,repeated.id);assert.equal((await call('/api/applications')).total,1)})
 await fill('.application-form select','APPLIED')
 await fill('.application-form label:nth-child(2) select',resume.id)
 await fill('.application-form input[type="datetime-local"]','2026-11-02T10:00')
 await fill('.application-form textarea','已提交申请，准备 Redis 缓存面试。')
 await button('保存进度')
 await until(async()=>(await text()).includes('进度已保存'),'save progress')
 app=await call('/api/applications/'+app.id)
 await check('UI saves stage, frozen resume and progress timeline',async()=>{assert.equal(app.status,'APPLIED');assert.equal(app.resumeVersionId,resume.id);assert.equal(app.resumeSnapshot.draft.summary,'有项目实践的应届生');assert.equal(app.events.length,2)})
 await cdp('Network.enable')
 await cdp('Network.setBlockedURLs',{urls:['*api/applications/*/status']})
 await fill('.application-form textarea','网络失败后的待保存备注')
 await button('保存进度')
 await until(()=>evaluate('!!document.querySelector(".workflow-error")'),'failed save')
 await check('failed save preserves form and the server version',async()=>{
   assert.equal(await evaluate('document.querySelector(".application-form textarea").value'),'网络失败后的待保存备注')
   assert.equal((await call('/api/applications/'+app.id)).version,app.version)
 })
 await cdp('Network.setBlockedURLs',{urls:[]})
 await button('保存进度')
 await until(async()=>!(await text()).includes('保存中…')&&(await text()).includes('进度已保存'),'retry saved')
 app=await call('/api/applications/'+app.id)
 await check('retry commits the retained changes',async()=>assert.equal(app.events.at(-1).note,'网络失败后的待保存备注'))
 await check('stale versions return HTTP 409',()=>call('/api/applications/'+app.id+'/status',{version:1,status:'TEST'},owner.token,'PATCH',409))
 await check('other account cannot read or overwrite the record',async()=>{await call('/api/applications/'+app.id,null,other.token,'GET',404);await call('/api/applications/'+app.id+'/status',{version:app.version,status:'TEST'},other.token,'PATCH',404)})
 await shot('applications-desktop.png')
 await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true})
 await check('mobile layout stays inside viewport',async()=>assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true))
 await shot('applications-mobile.png')
 await cdp('Emulation.clearDeviceMetricsOverride')
 await navigate('/applications?due=1')
 await until(()=>evaluate('document.querySelectorAll(".application-row").length===1'),'due action')
 await check('due action view returns the pending application',async()=>assert.equal((await call('/api/applications?due=true')).total,1))
 await stop(server);await boot()
 await check('server restart preserves applications, snapshots and favorites',async()=>{const restored=await call('/api/applications/'+app.id);assert.equal(restored.version,app.version);assert.equal(restored.jobSnapshot.description,app.jobSnapshot.description);assert.equal((await call('/api/jobs/favorites')).jobs.length,1)})
 await navigate('/applications?id='+encodeURIComponent(app.id))
 await until(()=>evaluate('!!document.querySelector(".application-form")'),'deep link after restart')
 await check('SPA direct navigation restores the selected record',async()=>assert.ok((await text()).includes('已提交申请')))
 // Exercise the real logout path before checking another account.
 await navigate('/applications')
 await until(()=>evaluate('!!document.querySelector(".topbar-logout")'),'logout control')
 await button('退出')
 await until(()=>evaluate('location.pathname==="/login"'),'logout')
 await evaluate('localStorage.setItem("resume-helper-auth-session",'+JSON.stringify(JSON.stringify(other))+')')
 await navigate('/applications')
 await until(async()=>(await text()).includes('暂无符合条件的投递记录'),'other account empty')
 await check('second account UI shows no previous records',async()=>assert.equal(await evaluate('document.querySelectorAll(".application-row").length'),0))
 await call('/api/resume/versions/'+resume.id,null,owner.token,'DELETE')
 await check('bound resume snapshot survives deleting the original version',async()=>assert.equal((await call('/api/applications/'+app.id)).resumeSnapshot.draft.summary,'有项目实践的应届生'))
 await call('/api/applications/'+app.id+'?version='+app.version,null,owner.token,'DELETE')
 await check('deleting application keeps the original favorite',async()=>{assert.equal((await call('/api/applications')).total,0);assert.equal((await call('/api/jobs/favorites')).jobs.length,1)})
 assert.deepEqual(browserErrors,[])
 console.log('PASS '+checks.length+' application workflow checks')
}
try{await main();await writeFile(join(artifacts,'result.json'),JSON.stringify({passed:checks.length,checks,browserErrors},null,2))}
catch(error){console.error(error);await writeFile(join(artifacts,'failure.txt'),String(error)+'\n'+(server?.output()||''));process.exitCode=1}
finally{socket?.close();for(const child of children.reverse())await stop(child)}
