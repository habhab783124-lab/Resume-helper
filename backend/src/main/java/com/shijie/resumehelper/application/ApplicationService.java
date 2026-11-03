package com.shijie.resumehelper.application;

import com.shijie.resumehelper.job.service.JobService;
import com.shijie.resumehelper.job.store.ImportedJobStore;
import com.shijie.resumehelper.resume.service.ResumeService;
import com.shijie.resumehelper.resume.domain.ResumeVersion;
import com.shijie.resumehelper.interview.InterviewStore;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static com.shijie.resumehelper.application.ApplicationRecord.*;

/** Mutations serialize on this single-instance service; clients must send the last seen version. */
@Service
public class ApplicationService {
    private final FileSystemApplicationStore store;
    private final JobService jobs;
    private final ImportedJobStore imports;
    private final ResumeService resumes;
    private final InterviewStore interviews;
    public ApplicationService(FileSystemApplicationStore store,JobService jobs,ImportedJobStore imports,ResumeService resumes,InterviewStore interviews){
        this.store=store;this.jobs=jobs;this.imports=imports;this.resumes=resumes;this.interviews=interviews;
    }
    public record Create(String jobId,String resumeVersionId){}
    public record Change(Long version,Status status,String note,String resumeVersionId,Instant nextActionAt,String interviewSessionId){}
    public record Page(int total,int offset,int limit,List<ApplicationRecord> items){}
    public synchronized Page list(String owner,Status status,boolean due,int offset,int limit) {
        if(offset<0 || limit<1 || limit>100)throw new IllegalArgumentException("分页参数无效");
        Instant now=Instant.now();
        var rows=store.list(owner).stream().filter(v->status==null||v.status()==status)
            .filter(v->!due||(v.status()!=Status.CLOSED && v.nextActionAt()!=null&&!v.nextActionAt().isAfter(now)))
            .sorted(Comparator.comparing(ApplicationRecord::updatedAt).reversed()).toList();
        return new Page(rows.size(),offset,limit,rows.stream().skip(offset).limit(limit).toList());
    }
    public synchronized ApplicationRecord get(String owner,String id){
        return store.list(owner).stream().filter(v->v.id().equals(id)).findFirst()
            .orElseThrow(()->new ApplicationProblem(404,"没有找到这条投递记录"));
    }
    public synchronized ApplicationRecord create(String owner,Create request){
        if(request==null || request.jobId()==null || request.jobId().isBlank() || request.jobId().length()>200)
            throw new IllegalArgumentException("请选择目标岗位");
        // Same owner + job is idempotent, including rapid double-clicks and retry after a lost response.
        var existing=store.list(owner).stream().filter(v->v.jobSnapshot().id().equals(request.jobId())).findFirst();
        if(existing.isPresent())return existing.get();
        var job=(request.jobId().startsWith("imported-")?imports.find(owner,request.jobId()):jobs.find(request.jobId()))
            .orElseThrow(()->new ApplicationProblem(404,"岗位不可用，请重新搜索或导入 JD"));
        ResumeVersion resume=resume(owner,request.resumeVersionId());
        Instant now=Instant.now();
        var app=new ApplicationRecord(UUID.randomUUID().toString(),owner,job,resume==null?"":resume.id(),resume,
            Status.PREPARING,null,null,now,now,1,List.of(event("CREATED",null,Status.PREPARING,"创建投递准备记录","",now)));
        store.save(owner,app);return app;
    }
    public synchronized ApplicationRecord change(String owner,String id,Change request,boolean noteOnly){
        var old=get(owner,id);
        if(request==null || request.version()==null || request.version()!=old.version())
            throw new ApplicationProblem(409,"记录已更新，请刷新后重新操作");
        Status status=noteOnly||request.status()==null?old.status():request.status();
        if(!allowed(old.status(),status))throw new IllegalArgumentException("不支持该进度转换，请先退回准备或选择相邻进度");
        String note=Objects.toString(request.note(),"").trim();
        if(note.length()>4000)throw new IllegalArgumentException("备注最多 4000 字");
        String session=Objects.toString(request.interviewSessionId(),"").trim();
        if(!session.isEmpty() && interviews.find(owner,session).isEmpty())
            throw new ApplicationProblem(404,"没有找到本账号的这轮面试");
        if(noteOnly && note.isEmpty() && session.isEmpty())throw new IllegalArgumentException("请填写备注或选择面试记录");
        ResumeVersion resume=old.resumeSnapshot();
        if(!noteOnly && request.resumeVersionId()!=null && !request.resumeVersionId().equals(old.resumeVersionId()))
            resume=resume(owner,request.resumeVersionId());
        Instant now=Instant.now();
        var events=new ArrayList<>(old.events());
        if(events.size()>=500)throw new IllegalArgumentException("单条投递最多保留 500 条事件");
        events.add(event(noteOnly?"NOTE":old.status()==status?"UPDATED":"STATUS_CHANGED",old.status(),status,note,session,now));
        Instant applied=old.appliedAt();
        if(applied==null && status!=Status.PREPARING && status!=Status.CLOSED)applied=now;
        var changed=new ApplicationRecord(old.id(),owner,old.jobSnapshot(),resume==null?"":resume.id(),resume,status,applied,
            noteOnly?old.nextActionAt():request.nextActionAt(),old.createdAt(),now,old.version()+1,events);
        store.save(owner,changed);return changed;
    }
    public synchronized void delete(String owner,String id,long version){
        if(get(owner,id).version()!=version)throw new ApplicationProblem(409,"记录已更新，请刷新后再删除");
        store.delete(owner,id);
    }
    private ResumeVersion resume(String owner,String id){
        if(id==null||id.isBlank())return null;
        return resumes.findVersion(owner,id).orElseThrow(()->new ApplicationProblem(404,"没有找到本账号的简历版本"));
    }
    private Event event(String type,Status from,Status to,String note,String session,Instant now){
        return new Event(UUID.randomUUID().toString(),type,from,to,note,now,session);
    }
    public static boolean allowed(Status from,Status to){
        if(from==to)return true;
        return switch(from){
            case PREPARING -> Set.of(Status.APPLIED,Status.CLOSED).contains(to);
            case APPLIED -> Set.of(Status.PREPARING,Status.TEST,Status.INTERVIEW,Status.OFFER,Status.CLOSED).contains(to);
            case TEST -> Set.of(Status.APPLIED,Status.INTERVIEW,Status.OFFER,Status.CLOSED).contains(to);
            case INTERVIEW -> Set.of(Status.APPLIED,Status.TEST,Status.OFFER,Status.CLOSED).contains(to);
            case OFFER -> Set.of(Status.INTERVIEW,Status.CLOSED).contains(to);
            case CLOSED -> to==Status.PREPARING;
        };
    }
}
