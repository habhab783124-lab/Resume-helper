export const APPLICATION_STATUSES = [
  {value:'PREPARING',label:'待投递'},{value:'APPLIED',label:'已投递'},
  {value:'TEST',label:'笔试'},{value:'INTERVIEW',label:'面试'},
  {value:'OFFER',label:'Offer'},{value:'CLOSED',label:'结束'}
]
const transitions = {
  PREPARING:['APPLIED','CLOSED'],APPLIED:['PREPARING','TEST','INTERVIEW','OFFER','CLOSED'],
  TEST:['APPLIED','INTERVIEW','OFFER','CLOSED'],INTERVIEW:['APPLIED','TEST','OFFER','CLOSED'],
  OFFER:['INTERVIEW','CLOSED'],CLOSED:['PREPARING']
}
export const statusLabel = value => APPLICATION_STATUSES.find(v=>v.value===value)?.label || value
export const allowedStatuses = value => APPLICATION_STATUSES.filter(v=>v.value===value||transitions[value]?.includes(v.value))
export function localDateTime(value) {
  if(!value)return ''
  const date=new Date(value)
  if(Number.isNaN(date.getTime()))return ''
  return new Date(date.getTime()-date.getTimezoneOffset()*60000).toISOString().slice(0,16)
}
export const dueNow = app => app.status!=='CLOSED' && Boolean(app.nextActionAt) && new Date(app.nextActionAt).getTime()<=Date.now()
export function safeSourceUrl(value) {
  try {const url=new URL(value);return ['https:','http:'].includes(url.protocol)&&!url.username&&!url.password?url.href:''}
  catch{return ''}
}
