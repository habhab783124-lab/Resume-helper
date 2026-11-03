package com.shijie.resumehelper.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.util.AtomicJsonFiles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One atomic owner file. No user-controlled path segments or partially committed memory cache. */
@Repository
public class FileSystemApplicationStore {
    private final Path root;
    private final ObjectMapper mapper;
    public FileSystemApplicationStore(@Value("${app.application.storage-dir:./data/applications}") String directory,ObjectMapper mapper){
        root=Path.of(directory).toAbsolutePath().normalize();this.mapper=mapper;
    }
    public synchronized List<ApplicationRecord> list(String owner) {
        Path file=file(owner);if(Files.notExists(file))return List.of();
        try {
            State state=mapper.readValue(file.toFile(),State.class);
            if(state==null || state.applications()==null)throw new IOException("投递文件结构无效");
            var ids=new HashSet<String>();
            for(var app:state.applications())if(app==null || !owner.equals(app.ownerId()) || !ids.add(app.id()))
                throw new IOException("投递文件身份或标识无效");
            return List.copyOf(state.applications());
        }catch(IOException e){throw new UncheckedIOException("读取投递记录失败，请保留原文件并检查存储",e);}
    }
    public synchronized void save(String owner,ApplicationRecord app) {
        if(!owner.equals(app.ownerId()))throw new IllegalArgumentException("投递身份不匹配");
        var records=new ArrayList<>(list(owner));records.removeIf(v->v.id().equals(app.id()));records.add(app);
        if(records.size()>1000)throw new IllegalArgumentException("最多保留 1000 条投递记录，请先整理旧记录");
        write(owner,records);
    }
    public synchronized void delete(String owner,String id){
        var records=new ArrayList<>(list(owner));records.removeIf(v->v.id().equals(id));write(owner,records);
    }
    private void write(String owner,List<ApplicationRecord> records){
        try{AtomicJsonFiles.write(mapper,file(owner),new State(records));}
        catch(IOException e){throw new UncheckedIOException("保存投递记录失败，请重试",e);}
    }
    private Path file(String owner){return root.resolve(AtomicJsonFiles.key(owner)+".json");}
    private record State(List<ApplicationRecord> applications){}
}
