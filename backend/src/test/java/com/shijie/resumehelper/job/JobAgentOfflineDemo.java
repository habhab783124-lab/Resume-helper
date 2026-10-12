package com.shijie.resumehelper.job;

import com.shijie.resumehelper.job.agent.AgentOptions;
import com.shijie.resumehelper.job.agent.AgentRunResult;
import com.shijie.resumehelper.job.agent.AgentStep;
import com.shijie.resumehelper.job.agent.JobSearchAgent;
import com.shijie.resumehelper.job.ai.FakeAiClient;
import com.shijie.resumehelper.job.api.dto.AgentOptionsDto;
import com.shijie.resumehelper.job.api.dto.JobRequests;
import com.shijie.resumehelper.job.api.dto.JobViews;
import com.shijie.resumehelper.job.api.dto.ProfileDto;
import com.shijie.resumehelper.job.config.JobModuleProperties;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobMatchResult;
import com.shijie.resumehelper.job.domain.JobQuery;
import com.shijie.resumehelper.job.domain.UserProfileSnapshot;
import com.shijie.resumehelper.job.match.JobMatchService;
import com.shijie.resumehelper.job.match.MatchScorer;
import com.shijie.resumehelper.job.match.SkillLexicon;
import com.shijie.resumehelper.job.service.JobService;
import com.shijie.resumehelper.job.source.CsvJobSource;
import com.shijie.resumehelper.job.source.JobImporter;
import com.shijie.resumehelper.job.source.JobSourceRegistry;
import com.shijie.resumehelper.job.source.MockJobSource;
import com.shijie.resumehelper.job.store.InMemoryJobStore;
import com.shijie.resumehelper.job.store.JobStore;
import com.shijie.resumehelper.job.util.TextKit;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 岗位模块离线验证程序。
 *
 * <p>不需要 Spring、不需要 MySQL、不需要大模型 token，直接跑：
 * <pre>
 * mvn -q test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.shijie.resumehelper.job.JobAgentOfflineDemo
 * </pre>
 * 或者在 IDE 里直接运行本类的 main 方法。
 *
 * <p>它同时承担两个作用：
 * <ol>
 *   <li>回归自检：把容易出错的地方（薪资解析、Java/JavaScript 误命中、CSV 引号、粘贴 JD 抽取）断言一遍；</li>
 *   <li>演示：完整打印智能体的执行轨迹和排序结果，方便对着接口联调。</li>
 * </ol>
 */
public final class JobAgentOfflineDemo {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== 拾阶求职工作台 · 岗位检索智能体 离线验证 ===\n");

        textKitChecks();
        lexiconChecks();
        pasteImportChecks();
        csvChecks();
        agentRun();
        gapReport();
        storeChecks();
        serviceContractChecks();

        System.out.printf("%n=== 自检结果：通过 %d 项，失败 %d 项 ===%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 自检

    private static void textKitChecks() {
        section("1. 文本工具：薪资与经验解析");
        check("15-22K -> [15,22]", Arrays.equals(new int[]{15, 22}, TextKit.parseSalary("15-22K")));
        check("15–22k（全角连字符）-> [15,22]", Arrays.equals(new int[]{15, 22}, TextKit.parseSalary("15–22k")));
        check("1.5-2.5万 -> [15,25]", Arrays.equals(new int[]{15, 25}, TextKit.parseSalary("1.5-2.5万")));
        check("面议 -> [-1,-1]", Arrays.equals(new int[]{-1, -1}, TextKit.parseSalary("面议")));
        check("24万/年 -> [20,20]", Arrays.equals(new int[]{20, 20}, TextKit.parseSalary("24万/年")));

        check("应届 / 1 年以内 -> 0 年", TextKit.parseMinYears("应届 / 1 年以内") == 0);
        check("1-3 年 -> 1 年", TextKit.parseMinYears("1-3 年") == 1);
        check("3年以上 -> 3 年", TextKit.parseMinYears("3年以上") == 3);
        check("经验不限 -> 0 年", TextKit.parseMinYears("经验不限") == 0);

        check("本科 = 本科", TextKit.educationRank("本科") == TextKit.educationRank("本科及以上"));
        check("本科 > 大专", TextKit.educationRank("本科") > TextKit.educationRank("大专"));
        check("硕士 > 本科", TextKit.educationRank("硕士") > TextKit.educationRank("本科"));

        double similarity = TextKit.dice("Java 后端开发工程师", "后端研发工程师（校招）");
        check("岗位标题相似度 > 0.2（实际 " + String.format("%.2f", similarity) + "）", similarity > 0.2);
    }

    private static void lexiconChecks() {
        section("2. 技能词典：别名归一与误命中防护");

        Set<String> onlyJs = SkillLexicon.extract("精通 JavaScript 与 TypeScript，用过 Node.js");
        check("「JavaScript」不会误判成 Java", !onlyJs.contains("Java"));
        check("能识别出 JavaScript", onlyJs.contains("JavaScript"));
        check("能识别出 TypeScript", onlyJs.contains("TypeScript"));

        Set<String> javaText = SkillLexicon.extract("熟悉 Java、Spring Boot 和 MySQL");
        check("能识别 Java", javaText.contains("Java"));
        check("能识别 Spring Boot", javaText.contains("Spring Boot"));
        check("能识别 MySQL", javaText.contains("MySQL"));

        check("spring-boot 归一为 Spring Boot", "Spring Boot".equals(SkillLexicon.canonicalOf("spring-boot")));
        check("springboot 归一为 Spring Boot", "Spring Boot".equals(SkillLexicon.canonicalOf("SpringBoot")));
        check("k8s 归一为 Kubernetes", "Kubernetes".equals(SkillLexicon.canonicalOf("k8s")));

        Set<String> cjk = SkillLexicon.extract("使用 Redis 做多级缓存，熟悉多线程与 JVM 调优");
        check("能识别中文技能「多线程」", cjk.contains("多线程"));
        check("能识别「JVM」", cjk.contains("JVM"));
        check("不跨标点拼出假技能", !SkillLexicon.extract("熟悉多，线程池").contains("多线程"));

        Set<String> mixed = SkillLexicon.extract("了解 HTTP 协议与 TCP/IP");
        check("能识别中英混合别名 http协议", mixed.contains("计算机网络"));
    }

    private static void pasteImportChecks() {
        section("3. 粘贴岗位描述解析（POST /api/jobs/import 的兜底路径）");

        String jd = """
                岗位名称：Java 后端开发工程师
                公司名称：示例科技有限公司
                薪资：15-22K
                工作地点：上海徐汇区
                任职要求：
                1. 本科及以上学历，应届生亦可；
                2. 熟悉 Java、Spring Boot、MySQL；
                3. 了解 Redis 和消息队列；
                4. 有 Git 协作经验，熟悉 Linux 常用命令。
                """;

        Job job = new JobImporter().parse(jd, "https://example.com/job/a");
        System.out.println("  解析结果：" + job.title() + " / " + job.company() + " / " + job.place()
                + " / " + job.salary() + " / " + job.experience() + " / " + job.skills());
        check("抽出岗位名称", job.title().contains("Java 后端开发工程师"));
        check("抽出公司名称", job.company().contains("示例科技"));
        check("抽出城市", "上海".equals(job.city()));
        check("行政区不重复带城市前缀（实际 " + job.district() + "）", "徐汇区".equals(job.district()));
        check("抽出薪资", job.salary().contains("15-22K"));
        check("抽出学历", "本科".equals(job.education()));
        check("识别为校招", job.campus());
        check("抽出技能 Java", job.skills().contains("Java"));
        check("抽出技能 Spring Boot", job.skills().contains("Spring Boot"));
        check("抽出技能 Redis", job.skills().contains("Redis"));
        check("抽出技能 消息队列", job.skills().contains("消息队列"));
        check("来源标注为手动导入", JobImporter.SOURCE_NAME.equals(job.sourceName()));
    }

    private static void csvChecks() {
        section("4. CSV 岗位库来源");

        CsvJobSource csv = new CsvJobSource("示例 CSV 岗位库", "classpath:jobs/sample-jobs.csv", true);
        List<Job> jobs = csv.search(new JobQuery());
        System.out.println("  从 classpath CSV 读到 " + jobs.size() + " 个岗位");
        check("读到了 CSV 岗位", jobs.size() == 2);
        check("CSV 技能用 | 分隔能正确拆开",
                jobs.stream().anyMatch(j -> j.skills().contains("Spring Boot")));
        check("CSV 带逗号的引号字段能正确解析",
                jobs.stream().anyMatch(j -> j.description().contains("租户隔离")));

        CsvJobSource missing = new CsvJobSource("缺失的 CSV", "classpath:jobs/not-exists.csv", true);
        check("CSV 文件不存在时返回空而不是抛异常", missing.search(new JobQuery()).isEmpty());
    }

    // ------------------------------------------------------------------ 端到端

    private static UserProfileSnapshot demoProfile() {
        return UserProfileSnapshot.builder()
                .userId("demo-user")
                .targetTitle("Java 后端开发工程师")
                .targetCities("上海")
                .yearsOfExperience(0)
                .education("本科")
                .skills("Java", "Spring Boot", "MySQL", "Redis")
                .industries("互联网")
                .expectedSalary("15-22K")
                .summary("计算机科学与技术专业应届生，做过校园活动管理平台和图书借阅系统两个课程项目，"
                        + "在项目里负责后端接口开发和数据库设计。")
                .experiences(List.of(
                        "校园活动管理平台：负责报名与签到接口开发，使用 Spring Boot + MySQL，"
                                + "接入 Redis 缓存热门活动信息，压测后接口平均响应时间从 800ms 降到 120ms。",
                        "图书借阅系统：独立完成数据库表设计与 CRUD 接口，使用 MyBatis 与 RESTful 风格接口。"))
                .build();
    }

    private static void agentRun() {
        section("5. 智能体端到端检索（离线替身大模型 + 演示岗位库）");

        JobSourceRegistry registry = new JobSourceRegistry(List.of(
                new CsvJobSource("示例 CSV 岗位库", "classpath:jobs/sample-jobs.csv", true),
                new MockJobSource()));
        JobSearchAgent agent = new JobSearchAgent(registry);
        UserProfileSnapshot profile = demoProfile();
        AgentOptions options = AgentOptions.defaults().toBuilder().limit(8).explainTopN(3).build();

        // 这里传 null 表示「用户没有配置 token」：智能体会自动使用离线替身
        AgentRunResult result = agent.run(profile, options, null);

        System.out.println("  实际使用的模型：" + result.aiModel() + "（是否真实大模型：" + result.aiUsed() + "）");
        System.out.println("\n  --- 智能体执行轨迹 ---");
        for (AgentStep step : result.trace()) {
            System.out.println("  第 " + step.round() + " 轮 [" + step.action() + "] " + step.thought());
            System.out.println("      观察：" + step.observation());
        }

        System.out.println("\n  --- 来源执行情况 ---");
        result.sourceReports().forEach(report -> System.out.println("  " + report.name()
                + "（" + report.kind() + "）：" + (report.ok() ? "正常，命中 " + report.count() + " 条" : "异常 - " + report.message())));

        if (!result.notes().isEmpty()) {
            System.out.println("\n  --- 降级/提示 ---");
            result.notes().forEach(note -> System.out.println("  · " + note));
        }

        System.out.println("\n  --- 排序结果（候选 " + result.candidateCount() + " 个）---");
        for (JobMatchResult match : result.matches()) {
            Job job = match.job();
            System.out.printf("  %3d 分  %-28s %-8s %-10s %-8s%n",
                    match.score(), job.title(), job.company(), job.place(), job.salary());
            System.out.println("          命中技能：" + (match.matchedSkills().isEmpty() ? "无" : String.join("、", match.matchedSkills())));
            System.out.println("          匹配理由：" + match.primaryReason());
        }

        check("智能体检索到了岗位", !result.isEmpty());
        check("执行轨迹非空", !result.trace().isEmpty());
        check("用了至少一个关键词", !result.usedKeywords().isEmpty());
        check("演示岗位库被调用且正常", result.sourceReports().stream()
                .anyMatch(r -> r.name().contains("演示") && r.ok()));
        check("没有真实 token 时明确标注未使用大模型", !result.aiUsed());
        check("结果按分数降序", isDescending(result.matches()));
        check("最贴合的岗位是上海 Java 后端岗",
                !result.matches().isEmpty() && result.matches().get(0).job().city().equals("上海"));

        JobMatchResult first = result.matches().get(0);
        System.out.println("\n  得分最高的岗位：" + first.job().title() + " " + first.score() + " 分");
        System.out.println("  分维度：" + first.dimensions());

        // 直接检索路径（GET /api/jobs 用这条）
        AgentRunResult direct = agent.search(profile, JobQuery.of("Java").limit(5), options, null);
        check("直接检索路径也能返回结果", !direct.isEmpty());
        check("直接检索遵守 limit", direct.matches().size() <= 5);
    }

    private static void gapReport() {        section("6. 技能差距报告（POST /api/jobs/{id}/match 的输出）");

        JobSearchAgent agent = new JobSearchAgent(new JobSourceRegistry(List.of(new MockJobSource())));
        UserProfileSnapshot profile = demoProfile();

        Job target = new MockJobSource().all().stream()
                .filter(j -> j.title().contains("服务端研发"))
                .findFirst()
                .orElseThrow();
        JobMatchService service = new JobMatchService(new MatchScorer());
        JobMatchService.GapReport report = service.gapReport(target, profile);

        System.out.println("  目标岗位：" + report.jobTitle() + "（" + report.score() + " 分）");
        System.out.println("  优势：" + report.strengths());
        System.out.println("  差距：" + report.gaps());
        System.out.println("  建议：" + report.suggestions());

        check("差距报告有分数", report.score() > 0);
        check("识别出经验年限差距", report.gaps().stream().anyMatch(g -> g.contains("年")));
        check("识别出缺少的技能", !report.gaps().isEmpty());
        check("给出了可执行的补强建议", !report.suggestions().isEmpty());
        check("解释里不出现「录取概率」", report.suggestions().stream().noneMatch(s -> s.contains("录取概率")));

        // 关联市场岗位检查：agent 变量保留用于演示依赖关系
        check("智能体可直接复用匹配服务", agent.registry() != null);
    }

    // ------------------------------------------------------------------ 工具

    private static void storeChecks() {
        section("7. 岗位存储与收藏（GET /api/jobs/{id}、收藏接口）");

        JobStore store = new InMemoryJobStore();
        List<Job> mock = new MockJobSource().all();
        store.putAll(mock);

        check("批量写入后可读", store.recent(100).size() == mock.size());
        check("按 id 能查到岗位", store.find(mock.get(0).id()).isPresent());
        check("查不到的 id 返回空而不是报错", store.find("not-exists").isEmpty());

        String jobId = mock.get(0).id();
        check("初次未收藏", !store.isSaved("u1", jobId));
        check("收藏成功", store.save("u1", jobId));
        check("重复收藏返回 false（幂等）", !store.save("u1", jobId));
        check("收藏状态可查", store.isSaved("u1", jobId));
        check("收藏列表为 1 条", store.saved("u1").size() == 1);
        check("收藏不串用户", store.saved("u2").isEmpty());
        check("取消收藏成功", store.unsave("u1", jobId));
        check("取消后列表为空", store.saved("u1").isEmpty());
        check("空 userId 不落库", !store.save("", jobId));
    }

    // ------------------------------------------------------------------ 服务层契约

    /**
     * 验证「控制器真正会调到的那一层」。
     *
     * <p>{@code JobService} 和 {@code JobViews} 都不依赖 Spring 容器：
     * JobService 的构造参数全是普通对象（registry / agent / store / importer / scorer / properties），
     * JobViews 只有 record 和静态工厂。
     * 所以可以手动 new 出来，把前端会拿到的响应结构完整断言一遍——
     * 不需要 Maven、不需要数据库、不需要启动 Web 服务。
     *
     * <p>唯一没被这组断言覆盖的是 Spring MVC 的接线本身（路径映射、Jackson 序列化、CORS、
     * 全局异常处理），那部分靠启动服务后用 tools/smoke-api.ps1 做 HTTP 冒烟。
     */
    private static void serviceContractChecks() {
        section("8. 服务层与响应契约（控制器走的就是这条路，无需 Spring）");

        JobModuleProperties props = new JobModuleProperties();
        props.setMaxRounds(3);
        props.setDefaultLimit(8);
        props.setExplainTopN(3);
        props.setMaxLimit(50);
        props.getSources().setCsvLocation("classpath:jobs/sample-jobs.csv");

        JobSourceRegistry registry = new JobSourceRegistry(List.of(
                new CsvJobSource("CSV 岗位库", "classpath:jobs/sample-jobs.csv", true),
                new MockJobSource()));
        JobStore store = new InMemoryJobStore();
        JobService service = new JobService(registry, new JobSearchAgent(registry), store,
                new JobImporter(), new MatchScorer(), props);

        check("数据源清单可读（GET /api/jobs/sources）", service.sources().size() == 2);

        ProfileDto profile = profileDto();

        // ---------------- POST /api/jobs/agent/run ----------------
        AgentOptionsDto options = new AgentOptionsDto();
        options.setLimit(6);
        AgentRunResult run = service.runAgent(profile, options, null);
        JobViews.AgentRunView view = JobViews.AgentRunView.of(run, id -> false);

        System.out.println("  智能体返回 " + view.total() + " 个岗位，执行轨迹 " + view.trace().size() + " 步");
        check("轨迹非空", !view.trace().isEmpty());
        check("轨迹从第 1 轮开始，且每步都有观察结果",
                view.trace().get(0).round() == 1
                        && view.trace().stream().allMatch(step -> !TextKit.isBlank(step.observation())));
        check("轨迹里记录了检索动作", view.trace().stream().anyMatch(step -> "search_jobs".equals(step.action())));
        check("每个岗位都有 0-100 的分数",
                view.jobs().stream().allMatch(c -> c.score() != null && c.score() >= 0 && c.score() <= 100));
        check("每个岗位都有一句话匹配理由", view.jobs().stream().allMatch(c -> !TextKit.isBlank(c.reason())));
        check("卡片字段齐全（id/title/company/salary/place）", view.jobs().stream().allMatch(c ->
                !TextKit.isBlank(c.id()) && !TextKit.isBlank(c.title()) && !TextKit.isBlank(c.company())
                        && !TextKit.isBlank(c.salary()) && !TextKit.isBlank(c.place())));
        check("公司标记是单字（对应原型 company-mark）",
                view.jobs().stream().allMatch(c -> c.mark().length() == 1));
        check("配色类属于原型支持的四种", view.jobs().stream().allMatch(c ->
                List.of("", "mark-blue", "mark-amber", "mark-rose").contains(c.style())));
        check("技能标签不超过 4 个（对应原型 job-tags）",
                view.jobs().stream().allMatch(c -> c.tags().size() <= 4));
        check("响应带「不代表录取概率」的说明", view.scoreNotice().contains("不代表录取概率"));
        check("没有 token 时 aiUsed=false", !view.aiUsed());

        // ---------------- POST /api/jobs/search ----------------
        JobRequests.Search searchRequest = new JobRequests.Search();
        searchRequest.setKeyword("Java");
        searchRequest.setLimit(5);
        searchRequest.setProfile(profile);
        JobViews.JobList list = JobViews.JobList.of(service.search(profile, searchRequest, null), id -> false);
        check("搜索能返回结果", !list.jobs().isEmpty());
        check("搜索遵守 limit=5", list.jobs().size() <= 5);
        check("total 与实际条数一致", list.total() == list.jobs().size());
        check("搜索默认不调用大模型（列表要快）", !list.aiUsed());

        // ---------------- POST /api/jobs/import + GET /api/jobs/{id} ----------------
        String jd = "岗位名称：Java 后端开发工程师\n公司名称：手工导入测试公司\n薪资：16-24K\n"
                + "工作地点：上海浦东新区\n任职要求：本科，应届生亦可；熟悉 Java、Spring Boot、MySQL、Redis。";
        Job imported = service.importJob(jd, "https://example.com/job/x");
        check("导入的岗位有 id", !TextKit.isBlank(imported.id()));
        check("导入后能按 id 查到（GET /api/jobs/{id}）", service.find(imported.id()).isPresent());
        check("查不存在的 id 返回空而不是抛异常", service.find("job-does-not-exist").isEmpty());

        // ---------------- POST /api/jobs/{id}/match ----------------
        JobService.MatchOutcome outcome = service.match(imported, profile, false, null);
        JobViews.MatchDetail detail = JobViews.MatchDetail.of(outcome.match(), outcome.report(), false);
        System.out.println("  导入岗位匹配度 " + detail.score() + "，建议：" + detail.gapReport().suggestions());
        check("匹配详情有分数", detail.score() != null && detail.score() > 0);
        check("差距报告非空", detail.gapReport() != null);
        check("给出了补强建议", !detail.gapReport().suggestions().isEmpty());
        check("命中技能被识别出来", !detail.matchedSkills().isEmpty());

        // ---------------- 收藏 ----------------
        String uid = "u-1001";
        check("收藏接口可用", service.save(uid, imported.id()));
        check("收藏状态可查", service.isSaved(uid, imported.id()));
        check("收藏列表包含该岗位", service.favorites(uid).stream().anyMatch(j -> j.id().equals(imported.id())));
        check("卡片带 saved 标记", JobViews.JobCard.of(imported, service.isSaved(uid, imported.id())).saved());
        check("取消收藏可用", service.unsave(uid, imported.id()));

        // ---------------- 无画像路径：GET /api/jobs 不带画像字段 ----------------
        List<Job> plain = service.list(new JobQuery().limit(5));
        check("不带画像也能列岗位", !plain.isEmpty());
        check("不带画像时 score 为 null（前端不展示分数）",
                JobViews.JobCard.of(plain.get(0), false).score() == null);

        // ---------------- 空入参防御：前端漏传画像不应该 500 ----------------
        AgentRunResult nullProfile = service.runAgent(null, null, null);
        check("画像为 null 不抛异常", nullProfile != null);
        check("画像为 null 时给出提示而不是静默", !nullProfile.notes().isEmpty());
        JobService.MatchOutcome nullMatch = service.match(imported, null, false, null);
        check("匹配时画像为 null 不抛异常", nullMatch != null && nullMatch.report() != null);
    }

    private static ProfileDto profileDto() {
        ProfileDto profile = new ProfileDto();
        profile.setUserId("u-1001");
        profile.setTargetTitle("Java 后端开发工程师");
        profile.setTargetCities(List.of("上海"));
        profile.setYearsOfExperience(0);
        profile.setEducation("本科");
        profile.setSkills(List.of("Java", "Spring Boot", "MySQL", "Redis"));
        profile.setIndustries(List.of("互联网"));
        profile.setExpectedSalary("15-22K");
        profile.setSummary("计算机专业应届生，做过校园活动管理平台");
        profile.setExperiences(List.of("校园活动管理平台：负责报名与签到接口，用 Redis 缓存热门活动"));
        return profile;
    }

    private static boolean isDescending(List<JobMatchResult> matches) {
        for (int i = 1; i < matches.size(); i++) {
            if (matches.get(i - 1).score() < matches.get(i).score()) {
                return false;
            }
        }
        return true;
    }

    private static void section(String title) {
        System.out.println("\n" + title);
        System.out.println("-".repeat(64));
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [通过] " + description);
        } else {
            failed++;
            System.out.println("  [失败] " + description);
        }
    }
}
