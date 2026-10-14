package com.shijie.resumehelper.resume.domain;

import com.shijie.resumehelper.common.util.TextKit;

import java.util.ArrayList;
import java.util.List;

/**
 * 一份简历的内容。产品方案 3.B：「一个通用、简洁的简历模板」+「输入目标职位或岗位描述后，
 * 生成定向简历内容」。
 *
 * <h2>为什么是"条目式"而不是自由排版</h2>
 * 方案里明确写了「首期不做自由拖拽排版和多模板市场」。所以这里只用一种单栏结构：
 * 抬头 → 简介 → 教育 → 经历 → 技能。模板只有一个，把精力放在内容质量上。
 *
 * <h2>经历为什么要拆成 title / meta / bullets</h2>
 * 画像是「每行一条」的字符串（功能一确认后写进去的），比如
 * {@code 上海璞云 · Java 后端开发实习生 · 2025.07—2025.09：参与了权限模块开发；把校验改成 Redis 缓存}。
 * 直接整行塞进简历会很丑，所以 {@link com.shijie.resumehelper.resume.service.ResumeService}
 * 会按「第一个冒号」拆成抬头 + 要点。拆不出来就整行当要点，绝不丢内容。
 *
 * @param name       姓名（抬头）
 * @param headline   抬头下的职位一行
 * @param summary    个人简介
 * @param contacts   联系方式（城市 / 电话 / 邮箱 / 期望薪资）
 * @param educations 教育经历
 * @param experiences 实习 / 工作经历
 * @param projects   项目经历
 * @param skills     技能
 * @param keywords   简历末尾的关键词行（通常是技能里最相关的几个，方便 HR 扫）
 */
public record ResumeDraft(
        String name,
        String headline,
        String summary,
        List<String> contacts,
        List<Entry> educations,
        List<Entry> experiences,
        List<Entry> projects,
        List<String> skills,
        List<String> keywords) {

    /**
     * 一条经历。
     *
     * @param title   抬头行，例如「上海璞云 · Java 后端开发实习生」
     * @param meta    副标题，例如「2025.07 — 2025.09」
     * @param bullets 要点，每条一行
     */
    public record Entry(String title, String meta, List<String> bullets) {
        public Entry {
            title = TextKit.trim(title);
            meta = TextKit.trim(meta);
            bullets = bullets == null ? List.of() : List.copyOf(bullets);
        }

        public boolean isEmpty() {
            return title.isEmpty() && bullets.isEmpty();
        }
    }

    public ResumeDraft {
        name = TextKit.trim(name);
        headline = TextKit.trim(headline);
        summary = TextKit.orEmpty(summary);
        contacts = contacts == null ? List.of() : List.copyOf(contacts);
        educations = educations == null ? List.of() : List.copyOf(educations);
        experiences = experiences == null ? List.of() : List.copyOf(experiences);
        projects = projects == null ? List.of() : List.copyOf(projects);
        skills = skills == null ? List.of() : List.copyOf(skills);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    /** 简历是否为空——空简历不该让用户白等一次打印。 */
    public boolean isEmpty() {
        return summary.isEmpty() && educations.isEmpty() && experiences.isEmpty()
                && projects.isEmpty() && skills.isEmpty();
    }

    /** 抬头有没有联系方式。简历页据此提示"抬头还缺电话邮箱"。 */
    public boolean hasContact() {
        return !contacts.isEmpty();
    }

    /** 全部要点，用于把 AI 建议关联回具体某一条。 */
    public List<String> allBullets() {
        List<String> all = new ArrayList<>();
        for (Entry entry : educations) {
            all.addAll(entry.bullets());
        }
        for (Entry entry : experiences) {
            all.addAll(entry.bullets());
        }
        for (Entry entry : projects) {
            all.addAll(entry.bullets());
        }
        return all;
    }

    /** 用于统计"内容量"，页面上提示够不够充实。 */
    public int contentLength() {
        int total = summary.length() + String.join("", skills).length();
        for (Entry entry : educations) {
            total += entry.title().length() + entry.bullets().stream().mapToInt(String::length).sum();
        }
        for (Entry entry : experiences) {
            total += entry.title().length() + entry.bullets().stream().mapToInt(String::length).sum();
        }
        for (Entry entry : projects) {
            total += entry.title().length() + entry.bullets().stream().mapToInt(String::length).sum();
        }
        return total;
    }
}
