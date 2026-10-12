<template>
<section class="page active" id="page-interview" data-title="模拟面试">
          <div class="page-intro interview-intro"><div><div class="eyebrow"><span class="eyebrow-line"></span>INTERVIEW ROOM / 05</div><h1>练习表达，建立从容<span class="greeting-dot">。</span></h1><p>围绕目标岗位进行一场专注的模拟面试。</p></div><span class="session-label"><i></i>模拟练习 · 不会录音</span></div>
          <div class="interview-layout">
            <div class="interview-main">
              <div class="interview-session-top"><div><span class="session-overline">SESSION 01 <i>·</i> JAVA BACKEND</span><h2>Java 后端开发工程师</h2><span class="session-company">通用技术面试 · 预计 15 分钟</span></div><button class="icon-button" data-toast="面试设置（演示）" aria-label="面试设置"><svg><use href="#i-more"/></svg></button></div>
              <div class="interview-progress"><div><span>面试进度</span><b id="interview-progress-label">第 1 题 <i>/</i> 5</b></div><div class="interview-progress-track"><i id="interview-progress-bar"></i></div></div>
              <div class="question-card"><div class="question-card-top"><span class="question-type"><i></i>项目深挖</span><span class="question-time"><svg><use href="#i-clock"/></svg>建议回答 2–3 分钟</span></div><div class="question-number">QUESTION <span id="question-number">01</span></div><h3 id="interview-question">请你介绍一下校园活动管理平台这个项目。你主要负责了哪些部分？</h3><div class="question-audio"><button class="audio-play" id="play-question"><svg><use href="#i-play"/></svg></button><div class="audio-track"><span class="audio-title">试听问题</span><span class="audio-wave" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i></span><span class="audio-duration">示例语音</span></div></div><p class="audio-note">语音为浏览器试听效果，完整版本可接入 TTS 服务。</p></div>
              <div class="answer-editor"><label for="interview-answer">你的回答 <span>围绕背景、行动与结果组织表达</span></label><textarea id="interview-answer" rows="6" placeholder="从项目背景开始，说说你遇到的问题、采取的行动，以及最终结果…"></textarea><div class="answer-tools"><span id="answer-word-count">0 字</span><span class="answer-tip">建议结合具体细节进行回答</span><button class="button button-primary" id="submit-answer">提交回答 <svg><use href="#i-arrow"/></svg></button></div></div>
              <div class="interview-footnote"><span class="footnote-lock">◉</span>这是一场练习。当前页面不会录制或保存你的语音。</div>
            </div>
            <aside class="interview-aside"><div class="aside-quote-mark">“</div><div class="eyebrow">A THOUGHT BEFORE YOU BEGIN</div><h3>不必急着<br>给出完美答案。</h3><p>面试更像一次专业对话。讲清楚你的思路、选择和真实贡献，就已经很好。</p><div class="aside-divider"></div><div class="interview-aside-meta"><span>本次练习关注</span><ul><li><i>01</i> 项目表达</li><li><i>02</i> 技术理解</li><li><i>03</i> 逻辑结构</li></ul></div><button class="text-button" data-page="report">查看上次练习报告 <svg><use href="#i-arrow"/></svg></button></aside>
          </div>
          <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>为认真准备的人，整理下一步。</span><span>产品预览 2026</span></footer>
        </section>
</template>
