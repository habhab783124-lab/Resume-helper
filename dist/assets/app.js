(() => {
  "use strict";

  const byId = (id) => document.getElementById(id);
  const loginScreen = byId("login-screen");
  const appShell = byId("app-shell");
  const toast = byId("toast");
  let toastTimer = null;

  const showToast = (message) => {
    if (!toast) return;
    toast.textContent = message;
    toast.classList.add("is-visible");
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => toast.classList.remove("is-visible"), 2800);
  };

  const enterWorkspace = (remember = false) => {
    loginScreen.hidden = true;
    appShell.classList.add("is-visible");
    if (remember) {
      localStorage.setItem("shijie-demo-session", "1");
      sessionStorage.removeItem("shijie-demo-session");
    } else {
      sessionStorage.setItem("shijie-demo-session", "1");
    }
  };

  const leaveWorkspace = () => {
    localStorage.removeItem("shijie-demo-session");
    sessionStorage.removeItem("shijie-demo-session");
    appShell.classList.remove("is-visible");
    loginScreen.hidden = false;
    byId("user-menu")?.classList.remove("is-open");
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  if (localStorage.getItem("shijie-demo-session") === "1" || sessionStorage.getItem("shijie-demo-session") === "1") {
    loginScreen.hidden = true;
    appShell.classList.add("is-visible");
  }

  const loginForm = byId("login-form");
  loginForm?.addEventListener("submit", (event) => {
    event.preventDefault();
    const email = byId("login-email");
    const password = byId("login-password");
    const emailError = byId("email-error");
    const passwordError = byId("password-error");
    emailError.textContent = "";
    passwordError.textContent = "";
    if (!email.value.trim() || !email.validity.valid) {
      emailError.textContent = "请输入有效的邮箱地址。";
      email.focus();
      return;
    }
    if (password.value.length < 6) {
      passwordError.textContent = "密码至少需要 6 位。";
      password.focus();
      return;
    }
    enterWorkspace(document.querySelector(".remember-option input")?.checked);
    showToast("已进入拾阶演示工作台");
  });

  byId("demo-login")?.addEventListener("click", () => {
    enterWorkspace(false);
    showToast("已进入演示工作台，无需账号验证");
  });

  byId("toggle-password")?.addEventListener("click", (event) => {
    const input = byId("login-password");
    const button = event.currentTarget;
    const visible = input.type === "password";
    input.type = visible ? "text" : "password";
    button.textContent = visible ? "隐藏" : "显示";
  });

  const pageNames = {
    dashboard: "工作台",
    profile: "个人资料",
    materials: "材料整理",
    resume: "简历工作台",
    jobs: "岗位匹配",
    interview: "模拟面试",
    report: "面试报告"
  };

  const showPage = (name) => {
    if (!pageNames[name]) return;
    document.querySelectorAll(".page").forEach((page) => page.classList.toggle("active", page.id === "page-" + name));
    document.querySelectorAll(".nav-item").forEach((item) => item.classList.toggle("active", item.dataset.page === name));
    byId("crumb-current").textContent = pageNames[name];
    byId("user-menu")?.classList.remove("is-open");
    if (window.matchMedia("(max-width: 700px)").matches) window.scrollTo({ top: 0, behavior: "smooth" });
  };

  document.addEventListener("click", (event) => {
    const pageControl = event.target.closest("[data-page]");
    if (pageControl) {
      event.preventDefault();
      showPage(pageControl.dataset.page);
      return;
    }
    const toastControl = event.target.closest("[data-toast]");
    if (toastControl) showToast(toastControl.dataset.toast);
  });

  byId("user-menu-button")?.addEventListener("click", (event) => {
    event.stopPropagation();
    byId("user-menu").classList.toggle("is-open");
  });
  document.addEventListener("click", () => byId("user-menu")?.classList.remove("is-open"));
  byId("logout-button")?.addEventListener("click", leaveWorkspace);

  const jobs = [
    { id: "job-01", company: "璞云科技", mark: "璞", style: "", title: "Java 后端开发工程师", salary: "15–22K", place: "上海 · 徐汇", experience: "应届 / 1 年以内", tags: ["Java", "Spring Boot", "MySQL"], score: 94, reason: "与你的 Java 项目经历和 Spring Boot 技能高度贴合" },
    { id: "job-02", company: "新界数科", mark: "新", style: "mark-blue", title: "后端研发工程师（校招）", salary: "16–25K", place: "上海 · 浦东", experience: "应届校招", tags: ["Java", "Redis", "微服务"], score: 91, reason: "岗位面向应届生，技术栈与你的目标方向接近" },
    { id: "job-03", company: "澄海智能", mark: "澄", style: "mark-amber", title: "软件开发工程师", salary: "14–20K", place: "上海 · 杨浦", experience: "应届 / 1 年以内", tags: ["Java", "SQL", "Spring"], score: 88, reason: "你的数据库与接口开发经验符合岗位基础要求" },
    { id: "job-04", company: "林序信息", mark: "林", style: "mark-rose", title: "Java 服务端研发", salary: "18–26K", place: "上海 · 静安", experience: "1–3 年", tags: ["Java", "MySQL", "分布式"], score: 84, reason: "核心语言匹配，建议补充并发与分布式项目案例" },
    { id: "job-05", company: "方寸云联", mark: "方", style: "", title: "初级后端工程师", salary: "13–19K", place: "上海 · 长宁", experience: "应届 / 经验不限", tags: ["Spring Boot", "Redis", "Linux"], score: 82, reason: "经验要求友好，可以重点展示项目中的服务端实践" },
    { id: "job-06", company: "知行数据", mark: "知", style: "mark-blue", title: "研发工程师 — 平台方向", salary: "16–24K", place: "上海 · 虹口", experience: "应届校招", tags: ["Java", "MySQL", "数据结构"], score: 80, reason: "专业背景与校招要求相符，建议加强算法题准备" },
    { id: "job-07", company: "青屿网络", mark: "青", style: "mark-amber", title: "Java 开发工程师", salary: "15–23K", place: "上海 · 闵行", experience: "1 年以内", tags: ["Java", "Spring", "消息队列"], score: 78, reason: "基础技术栈匹配，消息队列是可以继续学习的方向" },
    { id: "job-08", company: "未央科技", mark: "未", style: "mark-rose", title: "业务后端开发", salary: "14–21K", place: "上海 · 徐汇", experience: "应届 / 1 年以内", tags: ["Java", "REST API", "Docker"], score: 76, reason: "项目经验适配业务开发，建议在简历中突出协作流程" }
  ];

  const savedJobs = new Set();
  const jobGrid = byId("job-grid");
  const escapeHtml = (value) => String(value).replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));

  const renderJobs = (query = "") => {
    const normalized = query.trim().toLowerCase();
    const visible = jobs.filter((job) => {
      const searchable = (job.company + " " + job.title + " " + job.place + " " + job.experience + " " + job.tags.join(" ")).toLowerCase();
      return searchable.includes(normalized);
    });
    byId("job-result-count").textContent = String(visible.length).padStart(2, "0");
    if (!visible.length) {
      jobGrid.innerHTML = '<div class="empty-jobs">暂时没有找到相关岗位，试试职位名称、公司或技能关键词。</div>';
      return;
    }
    jobGrid.innerHTML = visible.map((job) => {
      const tagMarkup = job.tags.map((tag) => "<span>" + escapeHtml(tag) + "</span>").join("");
      const savedText = savedJobs.has(job.id) ? "已收藏" : "收藏岗位";
      const savedClass = savedJobs.has(job.id) ? "is-saved" : "";
      return '<article class="job-card" data-job-id="' + job.id + '">' +
        '<div class="job-card-top"><span class="company-mark ' + job.style + '">' + escapeHtml(job.mark) + '</span>' +
        '<div class="job-title-wrap"><h3>' + escapeHtml(job.title) + '</h3><p>' + escapeHtml(job.company) + '</p></div>' +
        '<span class="match-score">' + job.score + '<small>匹配度</small></span></div>' +
        '<div class="job-meta"><span><svg><use href="#i-pin"/></svg>' + escapeHtml(job.place) + '</span><span><svg><use href="#i-user"/></svg>' + escapeHtml(job.experience) + '</span></div>' +
        '<div class="job-tags">' + tagMarkup + '</div>' +
        '<div class="job-reason"><svg><use href="#i-spark"/></svg><span>' + escapeHtml(job.reason) + '</span></div>' +
        '<div class="job-card-bottom"><span class="job-salary">' + escapeHtml(job.salary) + ' <small>· 月薪</small></span>' +
        '<button class="job-card-action ' + savedClass + '" data-save-job="' + job.id + '">' + savedText + ' <svg><use href="#i-arrow"/></svg></button></div></article>';
    }).join("");
  };

  renderJobs();
  const renderDashboardJobs = () => {
    const target = byId("dashboard-job-list");
    target.innerHTML = jobs.slice(0, 3).map((job) =>
      '<article class="recommended-job" data-page="jobs"><div class="rec-job-head"><span class="rec-company-mark">' + escapeHtml(job.mark) +
      '</span><span class="rec-match">' + job.score + '%</span></div><strong>' + escapeHtml(job.title) + '</strong><p>' + escapeHtml(job.company) +
      ' · 上海</p><div class="rec-salary"><span>Java · 校招</span><b>' + escapeHtml(job.salary) + '</b></div></article>'
    ).join("");
  };
  renderDashboardJobs();

  byId("jobs-search")?.addEventListener("input", (event) => renderJobs(event.target.value));
  byId("global-search")?.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      showPage("jobs");
      byId("jobs-search").value = event.currentTarget.value;
      renderJobs(event.currentTarget.value);
      event.currentTarget.blur();
    }
  });
  document.addEventListener("keydown", (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
      event.preventDefault();
      byId("global-search")?.focus();
    }
    if (event.key === "Escape") byId("user-menu")?.classList.remove("is-open");
  });

  jobGrid?.addEventListener("click", (event) => {
    const saveButton = event.target.closest("[data-save-job]");
    if (saveButton) {
      event.stopPropagation();
      const id = saveButton.dataset.saveJob;
      if (savedJobs.has(id)) savedJobs.delete(id);
      else savedJobs.add(id);
      renderJobs(byId("jobs-search").value);
      showToast(savedJobs.has(id) ? "岗位已加入收藏" : "已取消收藏");
      return;
    }
    const card = event.target.closest("[data-job-id]");
    if (card) {
      const job = jobs.find((item) => item.id === card.dataset.jobId);
      if (job) showToast(job.company + " · " + job.reason);
    }
  });

  const uploadZone = byId("upload-zone");
  const fileInput = byId("file-input");
  byId("choose-files")?.addEventListener("click", (event) => {
    event.stopPropagation();
    fileInput.click();
  });
  uploadZone?.addEventListener("click", (event) => {
    if (!event.target.closest("button")) fileInput.click();
  });
  uploadZone?.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      fileInput.click();
    }
  });

  const addMaterial = (name, size, type = "file") => {
    const extension = name.split(".").pop().toLowerCase();
    const kind = type === "text" ? "TXT" : extension === "pdf" ? "PDF" : extension === "doc" || extension === "docx" ? "DOC" : "TXT";
    const className = kind === "DOC" ? "file-doc" : kind === "TXT" ? "file-text" : "file-pdf";
    const sizeLabel = type === "text" ? "文本材料" : size < 1024 * 1024 ? Math.max(1, Math.round(size / 1024)) + " KB" : (size / 1024 / 1024).toFixed(1) + " MB";
    const row = document.createElement("article");
    row.className = "material-item";
    row.innerHTML = '<span class="file-type ' + className + '">' + kind + '</span><div class="material-info"><strong>' + escapeHtml(name) +
      '</strong><span>' + (type === "text" ? "文本经历" : "本地选择") + " · " + sizeLabel + ' · 刚刚</span></div><span class="status-pill status-ready"><i></i>已选择</span>' +
      '<button class="icon-button" data-toast="当前原型不解析本地文件" aria-label="材料选项"><svg><use href="#i-more"/></svg></button>';
    byId("material-list").prepend(row);
    const count = byId("material-list").children.length;
    byId("material-count").textContent = String(count).padStart(2, "0");
    document.querySelector(".heading-count").textContent = String(count).padStart(2, "0");
  };

  fileInput?.addEventListener("change", (event) => {
    const files = Array.from(event.target.files || []);
    const tooLarge = files.filter((file) => file.size > 10 * 1024 * 1024);
    const accepted = files.filter((file) => file.size <= 10 * 1024 * 1024);
    accepted.forEach((file) => addMaterial(file.name, file.size));
    if (tooLarge.length) showToast("部分文件超过 10MB，未加入材料列表");
    else if (accepted.length) showToast("文件仅在本地原型中显示，尚未上传或解析");
    event.target.value = "";
  });
  ["dragenter", "dragover"].forEach((name) => uploadZone?.addEventListener(name, (event) => {
    event.preventDefault();
    uploadZone.classList.add("is-dragging");
  }));
  ["dragleave", "drop"].forEach((name) => uploadZone?.addEventListener(name, (event) => {
    event.preventDefault();
    uploadZone.classList.remove("is-dragging");
  }));
  uploadZone?.addEventListener("drop", (event) => {
    const files = Array.from(event.dataTransfer.files || []).filter((file) => file.size <= 10 * 1024 * 1024);
    files.forEach((file) => addMaterial(file.name, file.size));
    if (files.length) showToast("文件仅在本地原型中显示，尚未上传或解析");
  });
  byId("save-paste")?.addEventListener("click", () => {
    const field = byId("paste-text");
    if (!field.value.trim()) {
      showToast("先粘贴一段项目或实习经历");
      field.focus();
      return;
    }
    const title = "新建文本材料 " + new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" }) + ".txt";
    addMaterial(title, new Blob([field.value]).size, "text");
    field.value = "";
    showToast("材料已加入演示列表，页面关闭后不会保存");
  });

  byId("print-resume")?.addEventListener("click", () => window.print());
  byId("save-resume")?.addEventListener("click", () => showToast("简历草稿已保存到本次页面状态（演示）"));
  byId("ai-suggestion")?.addEventListener("click", () => showToast("建议：补充真实的响应时间、数据规模或效率变化，不要填写未经验证的数字。"));
  const resumeSummary = document.querySelector(".resume-textarea");
  resumeSummary?.addEventListener("input", () => {
    const preview = document.querySelector(".paper-section > p");
    if (preview) preview.textContent = resumeSummary.value;
  });

  const interviewQuestions = [
    "请你介绍一下校园活动管理平台这个项目。你主要负责了哪些部分？",
    "你为什么在项目中选择使用 Redis？当时具体解决了什么问题？",
    "如果报名接口在活动开始时访问量突然增加，你会怎样排查和改进？",
    "请讲一次你在项目开发中遇到的困难，以及你是如何解决的。",
    "如果有机会重新设计这个项目，你最想改进哪一部分？为什么？"
  ];
  let questionIndex = 0;
  const answerField = byId("interview-answer");
  answerField?.addEventListener("input", () => {
    byId("answer-word-count").textContent = answerField.value.trim().length + " 字";
  });

  byId("play-question")?.addEventListener("click", () => {
    if (!("speechSynthesis" in window) || !("SpeechSynthesisUtterance" in window)) {
      showToast("当前浏览器暂不支持语音试听，请阅读文字问题");
      return;
    }
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(byId("interview-question").textContent);
    utterance.lang = "zh-CN";
    utterance.rate = 0.94;
    const wave = document.querySelector(".audio-wave");
    const button = byId("play-question");
    utterance.onstart = () => {
      wave.classList.add("is-speaking");
      button.setAttribute("aria-label", "正在播放问题");
    };
    utterance.onend = () => {
      wave.classList.remove("is-speaking");
      button.setAttribute("aria-label", "试听问题");
    };
    utterance.onerror = () => {
      wave.classList.remove("is-speaking");
      showToast("语音试听暂不可用，请阅读文字问题");
    };
    window.speechSynthesis.speak(utterance);
  });

  byId("submit-answer")?.addEventListener("click", () => {
    if (!answerField.value.trim()) {
      showToast("先写下你的回答，再继续下一题");
      answerField.focus();
      return;
    }
    if (questionIndex < interviewQuestions.length - 1) {
      questionIndex += 1;
      const nextNumber = String(questionIndex + 1).padStart(2, "0");
      byId("interview-question").textContent = interviewQuestions[questionIndex];
      byId("question-number").textContent = nextNumber;
      byId("interview-progress-label").innerHTML = "第 " + (questionIndex + 1) + " 题 <i>/</i> 5";
      byId("interview-progress-bar").style.width = ((questionIndex + 1) / interviewQuestions.length) * 100 + "%";
      answerField.value = "";
      byId("answer-word-count").textContent = "0 字";
      showToast("演示已记录本轮完成状态，进入下一题");
    } else {
      questionIndex = 0;
      byId("interview-question").textContent = interviewQuestions[0];
      byId("question-number").textContent = "01";
      byId("interview-progress-label").innerHTML = "第 1 题 <i>/</i> 5";
      byId("interview-progress-bar").style.width = "20%";
      answerField.value = "";
      byId("answer-word-count").textContent = "0 字";
      showPage("report");
      showToast("演示面试已完成，查看示例反馈报告");
    }
  });
})();
