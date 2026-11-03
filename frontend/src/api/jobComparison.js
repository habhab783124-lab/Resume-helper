import http from './client'
export function compareJobs(jobIds,preference='balanced') {
  return http.post('/api/jobs/compare',{jobIds,preference})
}
