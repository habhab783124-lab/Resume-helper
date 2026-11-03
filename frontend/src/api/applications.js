import http from './client'
export const listApplications = params => http.get('/api/applications', { params })
export const createApplication = payload => http.post('/api/applications', payload)
export const fetchApplication = id => http.get('/api/applications/'+encodeURIComponent(id))
export const changeApplication = (id,payload) => http.patch('/api/applications/'+encodeURIComponent(id)+'/status',payload)
export const addApplicationEvent = (id,payload) => http.post('/api/applications/'+encodeURIComponent(id)+'/events',payload)
export const deleteApplication = (id,version) => http.delete('/api/applications/'+encodeURIComponent(id),{params:{version}})
export const listApplicationInterviews = () => http.get('/api/interviews',{params:{limit:100}})
