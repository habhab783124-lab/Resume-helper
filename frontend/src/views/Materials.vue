<template>
<section class="page active" id="page-materials" data-title="材料整理">
          <div class="page-intro"><div><div class="eyebrow"><span class="eyebrow-line"></span>MATERIALS / 02</div><h1>材料整理</h1><p>简历、项目记录、实习复盘，都可以成为你的职业素材。</p></div><span class="storage-badge">已整理 <b id="material-count">02</b> 份材料</span></div>
          <div class="materials-layout">
            <div class="materials-main">
              <section class="upload-zone" id="upload-zone" tabindex="0" role="button" aria-label="上传材料">
                <input id="file-input" type="file" accept=".pdf,.doc,.docx,.txt" multiple hidden>
                <span class="upload-illustration"><svg><use href="#i-upload"/></svg><span class="upload-spark">✳</span></span>
                <h2>把材料放在这里</h2><p>拖拽文件至此，或 <button type="button" class="inline-action" id="choose-files">浏览文件</button></p>
                <div class="upload-formats"><span>PDF</span><span>DOCX</span><span>TXT</span><i>单个文件不超过 10MB</i></div>
              </section>
              <section class="paste-panel panel"><div class="panel-heading"><div><div class="eyebrow">QUICK CAPTURE</div><h3>粘贴一段经历</h3></div><span class="capture-icon">⌘ V</span></div><textarea id="paste-text" rows="4" placeholder="可以粘贴项目经历、实习记录或一段尚未整理的想法…"></textarea><div class="paste-actions"><span>保存后可在个人资料中继续整理</span><button class="button button-dark" id="save-paste">保存这段材料 <svg><use href="#i-arrow"/></svg></button></div></section>
              <section class="materials-list-panel"><div class="panel-heading"><div><div class="eyebrow">YOUR LIBRARY</div><h3>已整理的材料 <span class="heading-count">02</span></h3></div><button class="text-button" data-toast="排序方式：最近更新">最近更新 <svg><use href="#i-chevron"/></svg></button></div><div class="material-list" id="material-list">
                <article class="material-item"><span class="file-type file-pdf">PDF</span><div class="material-info"><strong>林同学_个人简历.pdf</strong><span>简历 · 1.8 MB · 今天 09:42</span></div><span class="status-pill status-ready"><i></i>已提取</span><button class="icon-button" data-toast="打开材料预览（演示）" aria-label="打开材料"><svg><use href="#i-more"/></svg></button></article>
                <article class="material-item"><span class="file-type file-doc">DOC</span><div class="material-info"><strong>校园活动平台_项目复盘.docx</strong><span>项目经历 · 356 KB · 昨天 16:18</span></div><span class="status-pill status-ready"><i></i>已提取</span><button class="icon-button" data-toast="打开材料预览（演示）" aria-label="打开材料"><svg><use href="#i-more"/></svg></button></article>
              </div></section>
            </div>
            <aside class="materials-side"><div class="side-illustration"><span class="paper paper-one"></span><span class="paper paper-two"></span><span class="paper paper-three"><i></i><i></i><i></i></span><span class="paper-spark">✳</span></div><div class="eyebrow">A LITTLE NOTE</div><h3>先收集，再整理。<br>好经历值得被记下来。</h3><p>上传材料后，我们会尝试提取其中的教育、项目、实习和技能信息，供你检查确认。</p><div class="privacy-note"><span>◎</span><span>演示原型不会上传文件到服务器</span></div></aside>
          </div>
          <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>为认真准备的人，整理下一步。</span><span>产品预览 2026</span></footer>
        </section>
</template>
