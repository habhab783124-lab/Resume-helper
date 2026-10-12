<template>
<section class="page active" id="page-jobs" data-title="岗位匹配">
          <div class="page-intro"><div><div class="eyebrow"><span class="eyebrow-line"></span>OPPORTUNITIES / 04</div><h1>找到适合你的机会<span class="greeting-dot">。</span></h1><p>根据你的技能、经历和求职意向，整理值得进一步了解的岗位。</p></div><button class="button button-outline" data-toast="粘贴岗位描述后即可开始匹配（演示）">＋ 粘贴岗位描述</button></div>
          <div class="jobs-summary"><div class="jobs-summary-left"><span class="summary-pin"><svg><use href="#i-pin"/></svg></span><div><strong>上海 · Java 后端开发</strong><span>基于你的个人资料推荐 <b>8</b> 个岗位</span></div></div><div class="jobs-summary-right"><span>本次推荐更新</span><b>今天 09:30</b></div></div>
          <div class="jobs-toolbar"><label class="jobs-search"><svg><use href="#i-search"/></svg><input id="jobs-search" type="search" placeholder="搜索职位、公司或技能…"></label><div class="filter-group"><button class="filter-select" data-toast="筛选城市：上海">城市：上海 <svg><use href="#i-chevron"/></svg></button><button class="filter-select" data-toast="筛选经验：应届 / 不限">经验：应届 / 不限 <svg><use href="#i-chevron"/></svg></button><button class="filter-select" data-toast="排序：匹配度优先">匹配度优先 <svg><use href="#i-chevron"/></svg></button></div></div>
          <div class="job-results-header"><span>为你挑选 <b id="job-result-count">08</b> 个机会</span><span><i class="match-legend"></i>匹配度是资料贴合参考，不代表录取概率</span></div>
          <div class="job-grid" id="job-grid"></div>
          <div class="jobs-bottom-note"><span>—</span>岗位信息为课程原型演示数据，请以招聘方发布内容为准<span>—</span></div>
          <footer class="page-footer"><span>拾阶 · CAREER STUDIO</span><span>为认真准备的人，整理下一步。</span><span>产品预览 2026</span></footer>
        </section>
</template>
