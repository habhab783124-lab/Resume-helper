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
          <span class="eyebrow"><span class="eyebrow-line"></span>{{ mode === 'login' ? 'WELCOME BACK' : 'CREATE ACCOUNT' }}</span>
          <h2>{{ mode === 'login' ? '欢迎回来' : '创建账号' }}<span class="greeting-dot">。</span></h2>
          <p>{{ mode === 'login' ? '登录拾阶，继续你的求职准备。' : '用一个邮箱，开始整理你的求职材料。' }}</p>
        </div>

        <div class="login-mode-tabs" role="tablist" aria-label="登录或注册">
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'login'"
            :class="{ 'is-active': mode === 'login' }"
            @click="switchMode('login')"
          >登录</button>
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'register'"
            :class="{ 'is-active': mode === 'register' }"
            @click="switchMode('register')"
          >注册</button>
        </div>

        <p
          v-if="feedback"
          class="login-feedback"
          :class="`is-${feedbackType}`"
          :role="feedbackType === 'error' ? 'alert' : 'status'"
        >
          {{ feedback }}
        </p>

        <form
          v-if="mode === 'login'"
          class="login-form"
          id="login-form"
          autocomplete="off"
          @submit.prevent="submitLogin"
          @pointerdown="markUserInteraction"
          @keydown="markUserInteraction"
        >
          <label class="login-field" for="login-email">
            <span>邮箱地址</span>
            <input
              id="login-email"
              v-model.trim="email"
              type="email"
              autocomplete="off"
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
                autocomplete="off"
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

          <div class="login-options">
            <label class="remember-option">
              <input v-model="rememberLogin" type="checkbox">
              <span class="custom-checkbox"><svg><use href="#i-check" /></svg></span>
              记住登录状态
            </label>
            <button type="button" class="forgot-link" @click="showInfo('本版本不发送邮件，也没有找回密码入口；可以换一个邮箱重新注册。')">忘记密码？</button>
          </div>

          <button class="button button-primary login-submit" type="submit" :disabled="submitting">
            {{ submitting ? '正在登录…' : '登录拾阶' }} <svg v-if="!submitting"><use href="#i-arrow" /></svg>
          </button>

          <p class="login-switch-hint">
            还没有账号？<button type="button" @click="switchMode('register')">立即注册</button>
          </p>
        </form>

        <form
          v-else
          class="login-form"
          id="register-form"
          autocomplete="off"
          @submit.prevent="submitRegister"
          @pointerdown="markUserInteraction"
          @keydown="markUserInteraction"
        >
          <label class="login-field" for="register-name">
            <span>昵称<i class="login-field-hint">选填</i></span>
            <input
              id="register-name"
              v-model.trim="displayName"
              type="text"
              autocomplete="nickname"
              maxlength="24"
              placeholder="例如：林同学"
              @input="clearFeedback"
            >
          </label>

          <label class="login-field" for="register-email">
            <span>邮箱地址</span>
            <input
              id="register-email"
              v-model.trim="email"
              type="email"
              autocomplete="email"
              placeholder="name@example.com"
              required
              @input="clearFeedback"
            >
          </label>

          <label class="login-field" for="register-password">
            <span>设置密码<i class="login-field-hint">至少 8 位</i></span>
            <div class="password-input-wrap">
              <input
                id="register-password"
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="new-password"
                placeholder="请输入密码"
                minlength="8"
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

          <label class="login-field" for="register-password-confirm">
            <span>确认密码</span>
            <input
              id="register-password-confirm"
              v-model="confirmPassword"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="再输入一次密码"
              required
              @input="clearFeedback"
            >
          </label>

          <div class="login-agree-row">
            <label class="remember-option">
              <input v-model="agreeTerms" type="checkbox">
              <span class="custom-checkbox"><svg><use href="#i-check" /></svg></span>
              我已阅读并同意
            </label>
            <button type="button" @click="showInfo('服务条款将在完整版本中提供。')">服务条款</button>
            <span>与</span>
            <button type="button" @click="showInfo('隐私政策将在完整版本中提供。')">隐私政策</button>
          </div>

          <button class="button button-primary login-submit" type="submit" :disabled="submitting">
            {{ submitting ? '正在创建…' : '创建账号' }} <svg v-if="!submitting"><use href="#i-arrow" /></svg>
          </button>

          <p class="login-switch-hint">
            已经有账号？<button type="button" @click="switchMode('login')">去登录</button>
          </p>
        </form>

        <p class="login-terms">注册或登录即表示你同意我们的 <button type="button" @click="showInfo('服务条款将在完整版本中提供。')">服务条款</button> 与 <button type="button" @click="showInfo('隐私政策将在完整版本中提供。')">隐私政策</button></p>
        <div class="login-prototype-note"><span>ⓘ</span>账号保存在本机后端；密码只保存加盐哈希，本版本不发送验证邮件。</div>
      </div>
      <div class="login-right-footer"><span>SECURE ACCESS <i>·</i> YOUR PERSONAL CAREER SPACE</span><span>需要帮助？ <button type="button" @click="showInfo('帮助中心将在完整版本中开放。')">联系我们</button></span></div>
    </div>
  </section>
</template>

<script setup>
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { loginAccount, registerAccount } from '../api/auth'
import { saveAuthSession } from '../utils/authSession'

const route = useRoute()
const router = useRouter()
const mode = ref(route.query.mode === 'register' ? 'register' : 'login')
const email = ref('')
const password = ref('')
const confirmPassword = ref('')
const displayName = ref('')
const rememberLogin = ref(true)
const agreeTerms = ref(false)
const showPassword = ref(false)
const feedback = ref('')
const feedbackType = ref('info')
const submitting = ref(false)
let userInteracted = false
let autofillTimer = null

/**
 * 浏览器密码管理器可能在 Vue 挂载之后才把保存的账号填进输入框。
 * 只要用户还没开始操作，就把这类自动填充清掉，避免打开页面就要先删一遍。
 */
function clearBrowserAutofill() {
  if (userInteracted) return
  email.value = ''
  password.value = ''
  confirmPassword.value = ''
}

function markUserInteraction() {
  userInteracted = true
}

onMounted(() => {
  nextTick(clearBrowserAutofill)
  // 有些浏览器在 load 之后才填值，延迟再清一次。
  autofillTimer = window.setTimeout(clearBrowserAutofill, 250)
  window.addEventListener('pageshow', clearBrowserAutofill)
})

onUnmounted(() => {
  if (autofillTimer) window.clearTimeout(autofillTimer)
  window.removeEventListener('pageshow', clearBrowserAutofill)
})

function clearFeedback() {
  feedback.value = ''
}

function showInfo(message) {
  feedbackType.value = 'info'
  feedback.value = message
}

function showError(message) {
  feedbackType.value = 'error'
  feedback.value = message
}

function switchMode(next) {
  if (submitting.value) return
  userInteracted = true
  mode.value = next
  clearFeedback()
}

async function submitLogin() {
  if (submitting.value) return
  submitting.value = true
  clearFeedback()
  try {
    const result = await loginAccount({ email: email.value, password: password.value })
    saveAuthSession(result, rememberLogin.value)
    openWorkspace()
  } catch (error) {
    showError(error?.message || '登录失败，请稍后重试。')
  } finally {
    submitting.value = false
  }
}

async function submitRegister() {
  if (submitting.value) return
  clearFeedback()

  if (!agreeTerms.value) {
    showError('请先阅读并同意服务条款与隐私政策。')
    return
  }
  if (password.value.length < 8) {
    showError('密码至少需要 8 位。')
    return
  }
  if (password.value !== confirmPassword.value) {
    showError('两次输入的密码不一致。')
    return
  }

  submitting.value = true
  try {
    const result = await registerAccount({
      email: email.value,
      password: password.value,
      displayName: displayName.value
    })
    saveAuthSession(result, rememberLogin.value)
    openWorkspace()
  } catch (error) {
    showError(error?.message || '注册失败，请稍后重试。')
  } finally {
    submitting.value = false
  }
}

function openWorkspace() {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
  const destination = redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/dashboard'
  router.replace(destination)
}
</script>
