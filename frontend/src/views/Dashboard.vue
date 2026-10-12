<template>
  <div class="dashboard-container">
    <!-- 顶部导航栏 -->
    <header class="top-nav">
      <div class="nav-left">
        <el-icon class="nav-logo"><Platform /></el-icon>
        <span class="nav-title">AI 求职与模拟面试工作台</span>
        <el-tag type="success" size="small" effect="plain" class="status-tag">开发版 v0.1</el-tag>
      </div>
      <div class="nav-right">
        <el-avatar size="small" :icon="User" />
        <span class="username">{{ currentUser.username }}</span>
        <el-button type="danger" link @click="handleLogout">退出登录</el-button>
      </div>
    </header>

    <!-- 主体区域 -->
    <main class="main-content">
      <!-- 欢迎横幅 -->
      <el-card shadow="never" class="welcome-card">
        <div class="welcome-header">
          <div>
            <h2>欢迎回来，{{ currentUser.username }}！ 👋</h2>
            <p>基于课程产品方案，您的求职全流程分为以下 4 个阶段，点击即可开启体验。</p>
          </div>
          <el-button type="primary" size="large">开始新求职</el-button>
        </div>
      </el-card>

      <!-- 核心四大模块卡片 -->
      <div class="module-grid">
        <el-card shadow="hover" class="module-card">
          <div class="card-icon blue"><Document /></div>
          <h3>1. 材料管理与经历提取</h3>
          <p>上传 PDF/DOCX 简历或项目复盘，AI 自动提取教育、技能与项目经历。</p>
          <div class="card-footer">
            <el-tag size="small">准备中</el-tag>
            <el-link type="primary" :underline="false">进入材料库 →</el-link>
          </div>
        </el-card>

        <el-card shadow="hover" class="module-card">
          <div class="card-icon green"><EditPen /></div>
          <h3>2. 简历工作台</h3>
          <p>选择 2~3 套精美模板，根据目标岗位由大模型定向生成并给出修改建议。</p>
          <div class="card-footer">
            <el-tag size="small">准备中</el-tag>
            <el-link type="primary" :underline="false">管理简历 →</el-link>
          </div>
        </el-card>

        <el-card shadow="hover" class="module-card">
          <div class="card-icon purple"><Search /></div>
          <h3>3. 岗位匹配与差距分析</h3>
          <p>浏览公开招聘岗位或手动粘贴 JD，系统计算匹配评分并生成技能差距报告。</p>
          <div class="card-footer">
            <el-tag size="small">准备中</el-tag>
            <el-link type="primary" :underline="false">查看岗位 →</el-link>
          </div>
        </el-card>

        <el-card shadow="hover" class="module-card">
          <div class="card-icon orange"><Microphone /></div>
          <h3>4. AI 模拟面试</h3>
          <p>基于选定岗位进行真实多轮面试：AI 语音提问、麦克风录音作答与逐题反馈。</p>
          <div class="card-footer">
            <el-tag type="warning" size="small">核心模块</el-tag>
            <el-link type="primary" :underline="false">发起面试 →</el-link>
          </div>
        </el-card>
      </div>

      <!-- 项目状态与进度说明 -->
      <el-card shadow="never" class="info-card">
        <template #header>
          <div class="card-title">
            <span>🚀 课设开发下一步指引</span>
          </div>
        </template>
        <el-steps :active="1" finish-status="success" simple>
          <el-step title="搭建前端与登录界面" description="已就绪" />
          <el-step title="启动 Java 后端与 MySQL" description="下一步" />
          <el-step title="简历上传与 AI 提取" />
          <el-step title="岗位匹配与语音面试" />
        </el-steps>
      </el-card>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Platform, User, Document, EditPen, Search, Microphone } from '@element-plus/icons-vue'

const router = useRouter()
const currentUser = ref({ username: '用户' })

onMounted(() => {
  const saved = localStorage.getItem('user')
  if (saved) {
    try {
      currentUser.value = JSON.parse(saved)
    } catch (e) {}
  }
})

const handleLogout = () => {
  localStorage.removeItem('user')
  ElMessage.info('已退出登录')
  router.push('/login')
}
</script>

<style scoped>
.dashboard-container {
  min-height: 100vh;
  background-color: #f8fafc;
}

.top-nav {
  height: 60px;
  background-color: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.nav-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.nav-logo {
  font-size: 24px;
  color: #2563eb;
}

.nav-title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.status-tag {
  margin-left: 8px;
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.username {
  font-size: 14px;
  color: #334155;
  font-weight: 500;
}

.main-content {
  max-width: 1200px;
  margin: 24px auto;
  padding: 0 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.welcome-card {
  border-radius: 12px;
  background: linear-gradient(135deg, #ffffff 0%, #eff6ff 100%);
  border: 1px solid #dbeafe;
}

.welcome-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.welcome-header h2 {
  font-size: 20px;
  color: #1e3a8a;
  margin-bottom: 6px;
}

.welcome-header p {
  font-size: 14px;
  color: #64748b;
}

.module-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 20px;
}

.module-card {
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  transition: transform 0.2s, box-shadow 0.2s;
  cursor: pointer;
}

.module-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 10px 20px rgba(0, 0, 0, 0.08);
}

.card-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  margin-bottom: 14px;
}

.card-icon.blue { background-color: #dbeafe; color: #2563eb; }
.card-icon.green { background-color: #dcfce7; color: #16a34a; }
.card-icon.purple { background-color: #f3e8ff; color: #9333ea; }
.card-icon.orange { background-color: #ffedd5; color: #ea580c; }

.module-card h3 {
  font-size: 16px;
  color: #1e293b;
  margin-bottom: 8px;
}

.module-card p {
  font-size: 13px;
  color: #64748b;
  line-height: 1.5;
  min-height: 40px;
  margin-bottom: 16px;
}

.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid #f1f5f9;
}

.info-card {
  border-radius: 12px;
}

.card-title {
  font-weight: 600;
  color: #1e293b;
}
</style>
