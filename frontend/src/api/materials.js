import http from './client'

/**
 * 材料模块的接口封装（功能一，后端见 backend/src/main/java/.../material/api/）。
 *
 * 和其他 api 模块一样，返回的是**已解包的 data**，失败抛 `ApiError`。
 * 用户的 AI token 由 client.js 的请求拦截器统一加上（`X-AI-Api-Key`），
 * 这里不用管——材料抽取要用到大模型，所以带没带 token 直接决定抽取能不能成功。
 */

/** 已整理的材料列表（不含原文，列表不需要那么重）。 */
export function listMaterials() {
  return http.get('/api/materials')
}

/**
 * 上传一份材料并抽取。
 *
 * 后端是**同步抽取**（一份简历通常几百毫秒），所以返回时状态已经确定：
 * `material.status` 会是 `TEXT_EXTRACTED` / `SCANNED` / `FAILED` 之一，带着 `message`。
 *
 * @param {File} file
 * @param {(percent:number)=>void} [onProgress] 上传进度。抽取阶段不再有进度，页面要自己切文案。
 */
export function uploadMaterial(file, onProgress) {
  const form = new FormData()
  form.append('file', file, file.name)
  return http.post('/api/materials', form, {
    // 不手写 Content-Type：让浏览器自己带 boundary，否则后端解析不了 multipart
    headers: { 'Content-Type': undefined },
    // 上传大文件 + 后端抽取可能超过默认超时
    timeout: 120000,
    onUploadProgress: (event) => {
      if (onProgress && event.total) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    }
  })
}

/** 粘贴一段文本当材料。**不依赖文件解析**，扫描件也能用这条路。 */
export function pasteMaterial({ title, text }) {
  return http.post('/api/materials/text', { title, text }, { timeout: 120000 })
}

/** 材料详情：`{ material, extraction, text }`。`text` 就是抽出的原文，用于「原文对照」。 */
export function fetchMaterial(id) {
  return http.get(`/api/materials/${encodeURIComponent(id)}`)
}

/**
 * 只要原文（`text/plain`）。
 *
 * 详情接口里已经带了 `text`，一般不用单独调；留着它是为了排查问题方便，
 * 以及在将来详情响应瘦身时不用改调用方。
 */
export function fetchMaterialText(id) {
  return http.get(`/api/materials/${encodeURIComponent(id)}/text`, { responseType: 'text' })
}

/** 删除材料（原文件、原文、抽取结果一并删除）。 */
export function deleteMaterial(id) {
  return http.delete(`/api/materials/${encodeURIComponent(id)}`)
}

/** 读取服务端保存的求职画像。没保存过时抛 404。 */
export function fetchProfile() {
  return http.get('/api/profile')
}

/** 保存确认后的求职画像。字段与岗位模块的 profile 一致，可以互相透传。 */
export function saveProfile(profile) {
  return http.put('/api/profile', profile)
}
