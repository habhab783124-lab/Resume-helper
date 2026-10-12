<template>
  <section class="login-screen" id="login-screen" aria-label="登录拾阶">
    <div class="login-left">
      <a href="#" class="brand login-brand" aria-label="拾阶">
        <span class="brand-mark"><span></span><span></span><span></span></span>
        <span class="brand-word">拾阶<span class="brand-en">CAREER STUDIO</span></span>
      </a>
      <div class="login-editorial">
        <span class="login-edition">A CAREER COMPANION <i>·</i> SHANGHAI</span>
        <h1>认真准备，<br><em>从容前行。</em></h1>
        <p>一份简历，一次练习，一个更清晰的下一步。<br>把求职路上的每件事，慢慢整理好。</p>
        <div class="login-editorial-rule"></div>
        <div class="login-quote"><span>“</span><div><strong>让每一段经历，都找到恰当的表达。</strong><small>为认真准备的人，整理下一步。</small></div><b>01 / 03</b></div>
      </div>
      <div class="login-visual" aria-hidden="true">
        <span class="login-visual-label">YOUR NEXT CHAPTER</span>
        <div class="login-orbit orbit-outer"></div><div class="login-orbit orbit-inner"></div>
        <div class="login-orbit-core"><span class="orbit-core-mark">拾</span><span>CAREER<br>IN MOTION</span></div>
        <span class="login-orbit-dot dot-one"></span><span class="login-orbit-dot dot-two"></span><span class="login-orbit-dot dot-three"></span>
        <span class="login-visual-foot">SHANGHAI <i>·</i> 2026</span>
      </div>
      <div class="login-left-footer"><span>拾阶 · CAREER STUDIO</span><span>© 2026</span></div>
    </div>

    <div class="login-right">
      <div class="login-form-wrap">
        <div class="login-mobile-brand">
          <span class="brand-mark"><span></span><span></span><span></span></span>
          <span class="brand-word">拾阶<span class="brand-en">CAREER STUDIO</span></span>
        </div>
        <div class="login-heading">
          <span class="eyebrow"><span class="eyebrow-line"></span>WELCOME BACK</span>
          <h2>欢迎回来<span class="greeting-dot">。</span></h2>
          <p>登录拾阶，继续你的求职准备。</p>
        </div>

        <form class="login-form" id="login-form" @submit.prevent="submitLogin">
          <label class="login-field" for="login-email">
            <span>邮箱地址</span>
            <input
              id="login-email"
              v-model.trim="email"
              type="email"
              autocomplete="username"
              placeholder="name@example.com"
              required
              @input="clearFeedback"
            >
          </label>

          <label class="login-field" for="login-password">
            <span>登录密码</span>
            <div class="password-input-wrap">
              <input
                id="login-password"
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="current-password"
                placeholder="请输入密码"
                required
                @input="clearFeedback"
              >
              <button
                class="password-toggle"
                type="button"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >{{ showPassword ? '隐藏' : '显示' }}</button>
            </div>
          </label>

          <p v-if="feedback" class="login-feedback" :class="`is-${feedbackType}`" :role="feedbackType === 'error' ? 'alert' : 'status'">
            {{ feedback }}
          </p>

          <div class="login-options">
            <label class="remember-option">
              <input v-model="rememberLogin" type="checkbox">
              <span class="custom-checkbox"><svg><use href="#i-check" /></svg></span>
              记住登录状态
            </label>
            <button type="button" class="forgot-link" @click="showInfo('演示版本暂不提供密码找回功能。')">忘记密码？</button>
          </div>

          <button class="button button-primary login-submit" type="submit">
            登录拾阶 <svg><use href="#i-arrow" /></svg>
          </button>
          <div class="login-divider"><span></span><i>或者</i><span></span></div>
          <button class="button button-outline demo-login" type="button" @click="enterDemoWorkspace">
            <span class="demo-spark">✳</span>体验演示工作台
          </button>
        </form>

        <div class="login-demo-account" aria-label="演示账号信息">
          <span>演示账号</span>
          <code>{{ demoCredentials.email }}</code>
          <span class="demo-account-separator">·</span>
          <code>{{ demoCredentials.password }}</code>
        </div>
        <p class="login-terms">登录即表示你同意我们的 <button type="button" @click="showInfo('服务条款将在完整版本中提供。')">服务条款</button> 与 <button type="button" @click="showInfo('隐私政策将在完整版本中提供。')">隐私政策</button></p>
        <div class="login-prototype-note"><span>ⓘ</span>课程演示登录：只在浏览器本地校验，不会上传或保存密码；正式账号认证需接入后端。</div>
      </div>
      <div class="login-right-footer"><span>SECURE ACCESS <i>·</i> YOUR PERSONAL CAREER SPACE</span><span>需要帮助？ <button type="button" @click="showInfo('帮助中心将在完整版本中开放。')">联系我们</button></span></div>
    </div>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DEMO_CREDENTIALS, createDemoSession } from '../utils/demoAuth'

const route = useRoute()
const router = useRouter()
const email = ref('')
const password = ref('')
const rememberLogin = ref(true)
const showPassword = ref(false)
const feedback = ref('')
const feedbackType = ref('info')
const demoCredentials = DEMO_CREDENTIALS

function clearFeedback() {
  feedback.value = ''
}

function showInfo(message) {
  feedbackType.value = 'info'
  feedback.value = message
}

function submitLogin() {
  const result = createDemoSession(email.value, password.value, rememberLogin.value)
  if (!result.ok) {
    feedbackType.value = 'error'
    feedback.value = '邮箱或密码不正确，请使用页面下方的演示账号。'
    return
  }

  openWorkspace()
}

function enterDemoWorkspace() {
  email.value = demoCredentials.email
  password.value = demoCredentials.password
  createDemoSession(email.value, password.value, rememberLogin.value)
  openWorkspace()
}

function openWorkspace() {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
  const destination = redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/dashboard'
  router.replace(destination)
}
</script>
