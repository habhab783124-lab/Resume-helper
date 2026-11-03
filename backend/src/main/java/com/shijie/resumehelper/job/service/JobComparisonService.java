package com.shijie.resumehelper.job.service;

import com.shijie.resumehelper.common.profile.UserProfileSnapshot;
import com.shijie.resumehelper.common.util.TextKit;
import com.shijie.resumehelper.job.api.dto.JobViews;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.match.MatchScorer;
import com.shijie.resumehelper.job.match.SkillLexicon;
import com.shijie.resumehelper.job.store.ImportedJobStore;
import com.shijie.resumehelper.job.store.JobStore;
import com.shijie.resumehelper.material.store.ProfileStore;
import org.springframework.stereotype.Service;
import java.util.*;

/** Read-only comparison of retained snapshots. Never queries recruitment sites or an AI provider. */
@Service
public class JobComparisonService {
    public record Request(List<String> jobIds, String preference) {}
    public record Dimension(String label, Integer points, int weight, String note) {}
    public record Item(JobViews.JobCard job, Integer score, int knownWeight,
                       List<Dimension> dimensions, List<String> requiredSkills,
                       List<String> evidencedSkills, List<String> unrepresentedSkills,
                       Integer salaryMin, Integer salaryMax, List<String> notes) {}
    public record Comparison(String preference, boolean profileAvailable, String profileSummary,
                             List<Item> items, List<String> unavailableIds, String scoreNotice) {}
    private final JobStore jobs;
    private final ImportedJobStore imports;
    private final ProfileStore profiles;
    public JobComparisonService(JobStore jobs, ImportedJobStore imports, ProfileStore profiles) {
        this.jobs=jobs;this.imports=imports;this.profiles=profiles;
    }
    public Comparison compare(String owner, Request request) {
        if(request==null || request.jobIds()==null || request.jobIds().size()<2 || request.jobIds().size()>4)
            throw new IllegalArgumentException("请选择 2～4 个岗位进行对比");
        Set<String> ids=new LinkedHashSet<>();
        for(String id:request.jobIds()) {
            if(id==null || id.isBlank() || id.length()>200 || !ids.add(id))
                throw new IllegalArgumentException("岗位编号不能为空或重复，请重新选择");
        }
        String preference=request.preference()==null?"balanced":request.preference();
        MatchScorer.Weights weights=switch(preference) {
            case "balanced" -> MatchScorer.Weights.defaults();
            case "city" -> new MatchScorer.Weights(30,10,35,10,5,10);
            case "salary" -> new MatchScorer.Weights(25,10,10,10,5,40);
            case "skills" -> new MatchScorer.Weights(65,10,10,5,5,5);
            default -> throw new IllegalArgumentException("不支持的对比偏好");
        };
        // 只填写城市、学历或薪资也可进行相应维度比较；全空记录不能冒充已确认资料。
        var profile=profiles.find(owner).filter(p->p.isUsable() || !p.targetCities().isEmpty()
                || !p.education().isBlank() || !p.expectedSalary().isBlank() || p.yearsOfExperience()>0).orElse(null);
        var items=new ArrayList<Item>();var unavailable=new ArrayList<String>();
        for(String id:ids) {
            // Private imported IDs must never be recovered through the public snapshot store.
            var job=id.startsWith("imported-")?imports.find(owner,id):jobs.find(id);
            if(job.isEmpty())unavailable.add(id);
            else items.add(item(job.get(),profile,weights,jobs.isSaved(owner,id)));
        }
        // Equal scores retain selection order. Unknown scores are placed last.
        items.sort(Comparator.comparing(Item::score,Comparator.nullsLast(Comparator.reverseOrder())));
        String summary=profile==null?"尚无已确认资料":TextKit.joinNonBlank(" · ",profile.targetTitle(),
                String.join("、",profile.targetCities()),profile.expectedSalary());
        return new Comparison(preference,profile!=null,summary.isBlank()?"已读取本人确认资料":summary,
                List.copyOf(items),List.copyOf(unavailable),
                "偏好参考分仅用于比较资料贴合程度，不代表录取概率；未知维度按该项权重的 50% 中性计入，信息覆盖权重越低，越需要核对原文。不同偏好的分数不可直接比较。");
    }
    private Item item(Job job, UserProfileSnapshot profile, MatchScorer.Weights weights, boolean saved) {
        var required=new LinkedHashSet<>(SkillLexicon.extractFrom(job));
        // A target job title is an aspiration, never evidence of a possessed skill.
        var evidence=new LinkedHashSet<String>();
        if(profile!=null) {
            evidence.addAll(SkillLexicon.canonicalize(profile.skills()));
            evidence.addAll(SkillLexicon.extract(TextKit.joinNonBlank("\n",profile.summary(),
                    String.join("\n",profile.projects()),String.join("\n",profile.experiences()))));
        }
        var matched=required.stream().filter(evidence::contains).toList();
        var missing=profile==null?List.<String>of():required.stream().filter(s->!evidence.contains(s)).toList();
        var dimensions=new ArrayList<Dimension>();
        var notes=new ArrayList<String>();
        Map<String,Integer> raw=profile==null?Map.of():new MatchScorer(weights).score(job,profile).dimensions();
        boolean skillKnown=profile!=null&&!required.isEmpty()&&!evidence.isEmpty();
        dimensions.add(new Dimension("技能匹配",skillKnown?Math.round(weights.skill()*(float)matched.size()/required.size()):null,
                weights.skill(),profile==null?"请先确认个人资料":required.isEmpty()?"岗位技能信息不足":
                evidence.isEmpty()?"确认资料中尚无可识别技能证据":matched.size()+"/"+required.size()+" 项在确认资料中有体现；未体现不代表不会"));
        dimensions.add(dimension("岗位方向",weights.title(),profile!=null&&!profile.targetTitle().isBlank(),raw,
                "按岗位名称与目标职位的文本相似度计算","尚未填写目标职位"));
        boolean cityKnown=profile!=null&&!profile.targetCities().isEmpty()&&!job.city().isBlank();
        dimensions.add(dimension("城市",weights.city(),cityKnown,raw,cityKnown&&job.cityMatches(profile.targetCities())?
                "位于期望城市范围内":"与期望城市不一致",job.city().isBlank()?"岗位地点未披露":"尚未填写期望城市"));
        boolean experienceKnown=profile!=null&&(!profile.experiences().isEmpty()||!profile.projects().isEmpty()||profile.yearsOfExperience()>0)
                &&!job.experience().matches(".*(?:不限|未披露|未知).*");
        dimensions.add(dimension("经验",weights.experience(),experienceKnown,raw,
                "岗位要求："+job.experience()+"；确认资料相关经验："+(profile==null?0:profile.yearsOfExperience())+" 年",
                "经验信息待核对；请查看岗位原文与个人资料"));
        boolean educationKnown=profile!=null&&!profile.education().isBlank()&&TextKit.educationRank(job.education())>0;
        dimensions.add(dimension("学历",weights.education(),educationKnown,raw,
                "岗位要求："+job.education()+"；本人："+(profile==null?"":profile.education()),"学历要求或个人学历未明确"));
        boolean salaryKnown=profile!=null&&profile.expectedSalaryMin()>0&&job.salaryMax()>0;
        dimensions.add(dimension("薪资",weights.salary(),salaryKnown,raw,
                "比较岗位薪资区间上限与期望下限；不代表实际定薪",job.salaryMax()<=0?"岗位薪资未披露或无法换算":"尚未填写可识别的期望薪资"));
        int coverage=dimensions.stream().filter(d->d.points()!=null).mapToInt(Dimension::weight).sum();
        Integer score=profile==null||coverage==0?null:(int)Math.round(dimensions.stream()
                .mapToDouble(d->d.points()==null?d.weight()*0.5:d.points()).sum());
        if(job.salaryMax()>0)notes.add("薪资按原文金额折算为千元/月参考，税前或税后请向招聘方核对；日薪按 21.75 天、年薪按 12 个月折算，不计奖金、补贴和额外薪数。");
        if(!missing.isEmpty())notes.add("未体现技能请先自行核对，不会自动写入简历或个人资料。");
        notes.add("保留的是采集时的岗位快照，在招状态和抽取字段请以招聘原文为准。");
        return new Item(JobViews.JobCard.ofDetailed(job,saved),score,coverage,List.copyOf(dimensions),
                List.copyOf(required),matched,missing,job.salaryMin()>0?job.salaryMin():null,
                job.salaryMax()>0?job.salaryMax():null,List.copyOf(notes));
    }
    private Dimension dimension(String label,int weight,boolean known,Map<String,Integer> raw,String note,String unknown) {
        return new Dimension(label,known?raw.get(label):null,weight,known?note:unknown);
    }
}
