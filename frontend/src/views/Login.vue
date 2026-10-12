<template>
  <div class="login-wrapper">
    <div class="login-box">
      <!-- 左侧品牌介绍 -->
      <div class="brand-side">
        <div class="brand-content">
          <div class="logo-area">
            <el-icon class="logo-icon"><Platform /></el-icon>
            <h1>AI 求职与面试平台</h1>
          </div>
          <p class="slogan">智能驱动 · 20天课程实战项目</p>
          <div class="feature-list">
            <div class="feature-item">
              <el-icon><Document /></el-icon>
              <span>经历深度解析：智能提取简历与实习材料</span>
            </div>
            <div class="feature-item">
              <el-icon><EditPen /></el-icon>
              <span>定向简历生成：多模板匹配与大模型润色</span>
            </div>
            <div class="feature-item">
              <el-icon><Search /></el-icon>
              <span>岗位精准匹配：量化技能差距与匹配评分</span>
            </div>
            <div class="feature-item">
              <el-icon><Microphone /></el-icon>
              <span>全真模拟面试：语音交互 · 深度追问 · 逐题复盘</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧登录表单 -->
      <div class="form-side">
        <div class="form-header">
          <h2>欢迎登录</h2>
          <p>请输入您的账号与密码以进入工作台</p>
        </div>

        <el-form :model="form" :rules="rules" ref="formRef" class="login-form">
          <el-form-item prop="username">
            <el-input
              v-model="form.username"
              placeholder="请输入用户名 (如 admin)"
              size="large"
              :prefix-icon="User"
              clearable
            />
          </el-form-item>

          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码 (如 123456)"
              size="large"
              :prefix-icon="Lock"
              show-password
              @keyup.enter="handleLogin"
            />
          </el-form-item>

          <div class="form-options">
            <el-checkbox v-model="rememberMe">记住密码</el-checkbox>
            <el-link type="primary" :underline="false">忘记密码？</el-link>
          </div>

          <el-button
            type="primary"
            size="large"
            class="submit-btn"
            :loading="loading"
            @click="handleLogin"
          >
            登 录 工 作 台
          </el-button>

          <!-- 快捷演示账号填充 -->
          <div class="demo-account-box">
            <div class="demo-title">快捷填入测试账号：</div>
            <div class="demo-buttons">
              <el-button size="small" @click="fillAccount('admin', '123456')">
                管理员 (admin)
              </el-button>
              <el-button size="small" @click="fillAccount('student', '123456')">
                求职学生 (student)
              </el-button>
            </div>
          </div>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElNotification } from 'element-plus'
import { User, Lock, Platform, Document, EditPen, Search, Microphone } from '@element-plus/icons-vue'
import axios from 'axios'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)
const rememberMe = ref(true)

const form = reactive({
  username: 'admin',
  password: '123456'
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' }
  ]
}

const fillAccount = (username, password) => {
  form.username = username
  form.password = password
}

const handleLogin = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return

    loading.value = true
    try {
      // 优先尝试请求后端 Spring Boot 接口 (端口 8080)
      const res = await axios.post('http://localhost:8080/api/auth/login', {
        username: form.username,
        password: form.password
      }, { timeout: 1500 })

      if (res.data.code === 200) {
        ElMessage.success('后端验证通过，登录成功！')
        localStorage.setItem('user', JSON.stringify({ username: form.username }))
        router.push('/dashboard')
      } else {
        ElMessage.error(res.data.message || '用户名或密码错误')
      }
    } catch (err) {
      // 若后端尚未启动，提供体验模式本地直接登录
      if (form.password === '123456') {
        ElNotification({
          title: '体验模式登录成功',
          message: '检测到后端尚未启动，已使用本地模式进入工作台',
          type: 'success',
          duration: 3000
        })
        localStorage.setItem('user', JSON.stringify({ username: form.username }))
        router.push('/dashboard')
      } else {
        ElMessage.error('密码不正确 (测试密码为 123456)')
      }
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.login-wrapper {
  min-height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: linear-gradient(135deg, #1f2a44 0%, #293859 50%, #1e2840 100%);
  padding: 20px;
}

.login-box {
  width: 900px;
  max-width: 100%;
  min-height: 540px;
  background: #ffffff;
  border-radius: 16px;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.25);
  display: flex;
  overflow: hidden;
}

.brand-side {
  flex: 1;
  background: linear-gradient(145deg, #2563eb, #1d4ed8);
  color: #ffffff;
  padding: 48px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.logo-area {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.logo-icon {
  font-size: 36px;
}

.brand-side h1 {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.slogan {
  font-size: 14px;
  color: #bfdbfe;
  margin-bottom: 36px;
}

.feature-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.feature-item {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  color: #eff6ff;
  line-height: 1.5;
}

.feature-item .el-icon {
  font-size: 18px;
  color: #93c5fd;
}

.form-side {
  flex: 1.1;
  padding: 48px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.form-header h2 {
  font-size: 24px;
  color: #1e293b;
  font-weight: 700;
  margin-bottom: 8px;
}

.form-header p {
  font-size: 14px;
  color: #64748b;
  margin-bottom: 28px;
}

.form-options {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.submit-btn {
  width: 100%;
  font-size: 16px;
  font-weight: 600;
  border-radius: 8px;
  letter-spacing: 2px;
}

.demo-account-box {
  margin-top: 28px;
  padding: 14px;
  background-color: #f8fafc;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
}

.demo-title {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 8px;
}

.demo-buttons {
  display: flex;
  gap: 10px;
}

@media (max-width: 768px) {
  .login-box {
    flex-direction: column;
  }
  .brand-side {
    padding: 24px;
  }
  .form-side {
    padding: 24px;
  }
}
</style>
