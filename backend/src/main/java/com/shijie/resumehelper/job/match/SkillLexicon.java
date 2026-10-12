package com.shijie.resumehelper.job.match;

import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.util.TextKit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能词典：别名归一 + 从自由文本中识别技能。
 *
 * <p>实现要点（避免误命中）：
 * <ul>
 *   <li>拉丁文按「词」切分后生成 1~3 元组，所以 "Java" 不会命中 "JavaScript"。</li>
 *   <li>"Spring Boot" / "spring-boot" / "SpringBoot" 归一后都是 "springboot"，互相都能命中。</li>
 *   <li>纯中文技能（如「多线程」）在「只保留中文、拉丁词替换为分隔符」的字符流上匹配，
 *       避免「多，线程」被拼成「多线程」。</li>
 *   <li>中英混合技能（如「http协议」「redis缓存」）在「去掉一切非字母数字」的紧凑串上匹配。</li>
 * </ul>
 */
public final class SkillLexicon {

    /** 归一化别名 -> 规范技能名。 */
    private static final Map<String, String> ALIAS_TO_CANONICAL = new LinkedHashMap<>();
    /** 规范技能名 -> 该技能的全部归一化别名。 */
    private static final Map<String, Set<String>> CANONICAL_TO_ALIASES = new LinkedHashMap<>();

    private SkillLexicon() {
    }

    private static void register(String canonical, String... aliases) {
        Set<String> keys = CANONICAL_TO_ALIASES.computeIfAbsent(canonical, k -> new LinkedHashSet<>());
        keys.add(key(canonical));
        ALIAS_TO_CANONICAL.putIfAbsent(key(canonical), canonical);
        for (String alias : aliases) {
            String k = key(alias);
            if (!k.isEmpty()) {
                keys.add(k);
                ALIAS_TO_CANONICAL.putIfAbsent(k, canonical);
            }
        }
    }

    static {
        // ---- 语言与基础 ----
        register("Java", "javase", "javaee", "j2ee", "java8", "java11", "java17", "jdk");
        register("C++", "cpp");
        register("C#", "csharp", "dotnet");
        register("Python", "py");
        register("JavaScript", "js", "es6", "ecmascript");
        register("TypeScript", "ts");
        register("SQL");
        register("Shell", "bash", "shell脚本", "linux脚本");
        register("Scala");
        // ---- Java 后端生态 ----
        register("Spring", "springframework");
        register("Spring Boot", "springboot", "spring-boot");
        register("Spring MVC", "springmvc");
        register("Spring Cloud", "springcloud");
        register("Spring Cloud Alibaba", "springcloudalibaba");
        register("MyBatis", "mybatis");
        register("MyBatis-Plus", "mybatisplus");
        register("Hibernate", "jpa", "springdatajpa");
        register("Netty");
        register("Dubbo");
        register("RESTful", "restfulapi", "restapi", "rest");
        register("Servlet", "jsp");
        register("Tomcat", "jetty");
        register("Nginx");
        // ---- 数据库与中间件 ----
        register("MySQL", "mariadb");
        register("PostgreSQL", "pgsql", "postgres");
        register("Oracle");
        register("Redis", "redis缓存");
        register("MongoDB", "mongo");
        register("Elasticsearch", "es", "elasticsearch全文检索");
        register("Kafka");
        register("RabbitMQ", "rabbitmq消息队列");
        register("RocketMQ");
        register("消息队列", "mq");
        register("缓存", "缓存设计", "多级缓存");
        // ---- 工程与运维 ----
        register("Docker", "容器化");
        register("Kubernetes", "k8s");
        register("Linux", "unix", "centos", "ubuntu");
        register("Git", "github", "gitlab");
        register("Maven");
        register("Gradle");
        register("Jenkins", "ci/cd", "cicd", "持续集成");
        register("阿里云", "aws", "云服务");
        // ---- 计算机基础 ----
        register("数据结构");
        register("算法", "算法题", "leetcode");
        register("计算机网络", "tcp/ip", "http协议", "网络协议");
        register("操作系统");
        register("设计模式");
        register("多线程", "并发", "并发编程", "juc");
        register("JVM", "jvm调优", "java虚拟机");
        register("性能优化", "调优", "sql优化", "索引优化");
        register("单元测试", "junit", "mockito", "测试用例");
        register("微服务", "服务拆分");
        register("分布式", "分布式系统", "分布式事务");
        register("高并发", "高可用", "秒杀");
        // ---- 前端 ----
        register("Vue", "vue2", "vue3");
        register("React");
        register("HTML", "html5");
        register("CSS", "css3", "scss");
        register("Element Plus", "elementui", "element ui");
        // ---- 数据方向 ----
        register("Hadoop");
        register("Spark");
        register("Flink");
        register("Hive");
        register("机器学习", "深度学习");
    }

    /** 词典归一化：只保留字母、数字、+、#，并转小写。"Spring Boot" 与 "spring-boot" 都会得到 "springboot"。 */
    public static String key(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '+' || c == '#') {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 把用户填写的技能或岗位标签归一到规范技能名；词典里没有的原样返回。 */
    public static String canonicalOf(String raw) {
        String trimmed = TextKit.trim(raw);
        if (trimmed.isEmpty()) {
            return "";
        }
        String canonical = ALIAS_TO_CANONICAL.get(key(trimmed));
        return canonical != null ? canonical : trimmed;
    }

    /** 批量归一化并去重，保持输入顺序。 */
    public static List<String> canonicalize(Collection<String> raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (String item : raw) {
                String canonical = canonicalOf(item);
                if (!canonical.isEmpty()) {
                    result.add(canonical);
                }
            }
        }
        return List.copyOf(result);
    }

    /** 从自由文本中识别出现过的规范技能名。 */
    public static Set<String> extract(String text) {
        Index index = Index.of(text);
        Set<String> found = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : CANONICAL_TO_ALIASES.entrySet()) {
            for (String aliasKey : entry.getValue()) {
                if (index.contains(aliasKey)) {
                    found.add(entry.getKey());
                    break;
                }
            }
        }
        return found;
    }

    /** 岗位文本（标题 + 技能标签 + 描述）里出现的技能；来源标注的技能标签无条件保留。 */
    public static Set<String> extractFrom(Job job) {
        String text = TextKit.joinNonBlank("\n", job.title(), String.join(" ", job.skills()), job.description());
        Set<String> found = new LinkedHashSet<>(extract(text));
        found.addAll(canonicalize(job.skills()));
        return found;
    }

    /** 判断一段文本是否提到了某个技能。 */
    public static boolean textMentions(String text, String skill) {
        Index index = Index.of(text);
        String canonical = canonicalOf(skill);
        Set<String> aliases = CANONICAL_TO_ALIASES.get(canonical);
        if (aliases == null) {
            String k = key(skill);
            return !k.isEmpty() && index.contains(k);
        }
        for (String aliasKey : aliases) {
            if (index.contains(aliasKey)) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> allCanonicals() {
        return Set.copyOf(CANONICAL_TO_ALIASES.keySet());
    }

    /**
     * 文本索引：拉丁词元（1~3 元组）、纯中文流、紧凑串。
     */
    static final class Index {
        private final Set<String> latinKeys;
        private final String cjkText;
        private final String compactText;

        private Index(Set<String> latinKeys, String cjkText, String compactText) {
            this.latinKeys = latinKeys;
            this.cjkText = cjkText;
            this.compactText = compactText;
        }

        static Index of(String text) {
            Set<String> keys = new LinkedHashSet<>();
            StringBuilder cjk = new StringBuilder();
            StringBuilder compact = new StringBuilder();
            List<String> runs = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            String value = TextKit.orEmpty(text);

            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (isLatinWordChar(c)) {
                    current.append(c);
                    compact.append(Character.toLowerCase(c));
                } else {
                    if (current.length() > 0) {
                        runs.add(current.toString());
                        current.setLength(0);
                    }
                    if (isCjk(c)) {
                        cjk.append(c);
                        compact.append(c);
                    } else {
                        // 用分隔符断开中文，避免「多，线程」被拼成「多线程」
                        cjk.append('\u0000');
                    }
                }
            }
            if (current.length() > 0) {
                runs.add(current.toString());
            }

            for (int i = 0; i < runs.size(); i++) {
                StringBuilder gram = new StringBuilder();
                for (int n = 0; n < 3 && i + n < runs.size(); n++) {
                    gram.append(key(runs.get(i + n)));
                    keys.add(gram.toString());
                }
            }
            return new Index(keys, cjk.toString(), compact.toString());
        }

        boolean contains(String aliasKey) {
            if (aliasKey == null || aliasKey.isEmpty()) {
                return false;
            }
            boolean hasLatin = false;
            boolean hasCjk = false;
            for (int i = 0; i < aliasKey.length(); i++) {
                char c = aliasKey.charAt(i);
                if (isCjk(c)) {
                    hasCjk = true;
                } else {
                    hasLatin = true;
                }
            }
            if (hasLatin && hasCjk) {
                // 中英混合别名（http协议 / redis缓存）走紧凑串
                return compactText.contains(aliasKey);
            }
            if (hasCjk) {
                // 纯中文别名走「中文流」，不跨标点
                return cjkText.contains(aliasKey);
            }
            // 纯拉丁别名走词元，避免 Java 命中 JavaScript
            return latinKeys.contains(aliasKey);
        }

        private static boolean isLatinWordChar(char c) {
            return c < 128 && (Character.isLetterOrDigit(c) || c == '+' || c == '#');
        }

        private static boolean isCjk(char c) {
            return c >= 0x4E00 && c <= 0x9FFF;
        }
    }
}
