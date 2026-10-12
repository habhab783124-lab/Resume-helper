import { createRouter, createWebHistory } from 'vue-router'
import AppShell from '../layouts/AppShell.vue'
import Dashboard from '../views/Dashboard.vue'
import Interview from '../views/Interview.vue'
import Jobs from '../views/Jobs.vue'
import Login from '../views/Login.vue'
import Materials from '../views/Materials.vue'
import Profile from '../views/Profile.vue'
import Report from '../views/Report.vue'
import Resume from '../views/Resume.vue'

const routes = [
  { path: '/', redirect: '/login' },
  { path: '/login', name: 'Login', component: Login },
  {
    path: '/',
    component: AppShell,
    children: [
      { path: 'dashboard', name: 'Dashboard', component: Dashboard, meta: { title: '工作台' } },
      { path: 'profile', name: 'Profile', component: Profile, meta: { title: '个人资料' } },
      { path: 'materials', name: 'Materials', component: Materials, meta: { title: '材料整理' } },
      { path: 'resume', name: 'Resume', component: Resume, meta: { title: '简历工作台' } },
      { path: 'jobs', name: 'Jobs', component: Jobs, meta: { title: '岗位匹配' } },
      { path: 'interview', name: 'Interview', component: Interview, meta: { title: '模拟面试' } },
      { path: 'report', name: 'Report', component: Report, meta: { title: '面试报告' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/login' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
