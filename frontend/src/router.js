import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/capabilities', component: () => import('./views/Capabilities.vue'), meta: { title: '能力中心' } },
{ path: '/dashboard', component: () => import('./views/Dashboard.vue'), meta: { title: '概览' } },
  { path: '/materials', component: () => import('./views/Materials.vue'), meta: { title: '素材库' } },
  { path: '/media-tools', component: () => import('./views/MediaTools.vue'), meta: { title: '媒体工具' } },
  { path: '/crawl', component: () => import('./views/Crawl.vue'), meta: { title: '素材抓取' } },
  { path: '/ai', component: () => import('./views/AiSettings.vue'), meta: { title: 'AI 接入' } },
  { path: '/ai-create', component: () => import('./views/AiCreate.vue'), meta: { title: 'AI 创作' } },
  { path: '/projects', component: () => import('./views/Projects.vue'), meta: { title: '项目' } },
  { path: '/workflows', component: () => import('./views/Workflows.vue'), meta: { title: '工作流 / Skill' } },
  { path: '/fixed-order-presets', component: () => import('./views/FixedOrderPresets.vue'), meta: { title: '产片固定顺序' } },
  { path: '/tutorial', component: () => import('./views/Tutorial.vue'), meta: { title: '内制教程' } },
  { path: '/studio', component: () => import('./views/Studio.vue'), meta: { title: '出片控制台' } },
  { path: '/outputs', component: () => import('./views/Outputs.vue'), meta: { title: '成片库' } },
  { path: '/editor', component: () => import('./views/Editor.vue'), meta: { title: '成片编辑' } },
  { path: '/infinite-canvas', redirect: '/ai-create' },
  { path: '/resource-center', component: () => import('./views/ResourceCenter.vue'), meta: { title: '资源中心' } },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
