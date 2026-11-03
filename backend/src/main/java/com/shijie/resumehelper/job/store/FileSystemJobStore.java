package com.shijie.resumehelper.job.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.util.AtomicJsonFiles;
import com.shijie.resumehelper.job.domain.Job;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Public job snapshots and owner-scoped favorites survive restarts. Private imports stay separate. */
public class FileSystemJobStore implements JobStore {
    private final InMemoryJobStore cache = new InMemoryJobStore();
    private final Path root;
    private final ObjectMapper mapper;
    private final Map<String,LinkedHashSet<String>> favorites=new HashMap<>();
    public FileSystemJobStore(String directory,ObjectMapper mapper) {
        root=Path.of(directory).toAbsolutePath().normalize();this.mapper=mapper;
    }
    @Override public synchronized void put(Job job) {
        if (job.id().startsWith("imported-") || "手动导入".equals(job.sourceName())) return;
        if (cache.find(job.id()).filter(job::equals).isPresent()) return;
        try { AtomicJsonFiles.write(mapper,publicFile(job.id()),job); }
        catch(IOException e) { throw new UncheckedIOException("保存岗位快照失败，请重试",e); }
        cache.put(job);
    }
    @Override public synchronized void putAll(Collection<Job> jobs) { for(Job job:jobs) put(job); }
    @Override public synchronized Optional<Job> find(String id) {
        if(id==null || id.isBlank() || id.startsWith("imported-")) return Optional.empty();
        var cached=cache.find(id);if(cached.isPresent())return cached;
        Path file=publicFile(id);if(Files.notExists(file))return Optional.empty();
        try {
            Job job=mapper.readValue(file.toFile(),Job.class);
            if(!id.equals(job.id()))throw new IOException("岗位快照标识不匹配");
            cache.put(job);return Optional.of(job);
        }catch(IOException e){throw new UncheckedIOException("读取岗位快照失败",e);}
    }
    @Override public List<Job> recent(int limit) { return cache.recent(limit); }
    @Override public synchronized boolean save(String owner,String id) {
        var next=new LinkedHashSet<>(ids(owner));if(!next.add(id))return false;
        commit(owner,next);return true;
    }
    @Override public synchronized boolean unsave(String owner,String id) {
        var next=new LinkedHashSet<>(ids(owner));if(!next.remove(id))return false;
        commit(owner,next);return true;
    }
    @Override public synchronized boolean isSaved(String owner,String id){return ids(owner).contains(id);}
    @Override public synchronized List<Job> saved(String owner){
        var list=new ArrayList<>(ids(owner));Collections.reverse(list);
        return list.stream().map(this::find).flatMap(Optional::stream).toList();
    }
    private LinkedHashSet<String> ids(String owner) {
        if(favorites.containsKey(owner))return favorites.get(owner);
        Path file=favoriteFile(owner);
        try {
            List<String> values=Files.notExists(file)?List.of():mapper.readValue(file.toFile(),mapper.getTypeFactory().constructCollectionType(List.class,String.class));
            if(values==null || values.stream().anyMatch(Objects::isNull))throw new IOException("收藏文件结构无效");
            var restored=new LinkedHashSet<>(values);favorites.put(owner,restored);return restored;
        }catch(IOException e){throw new UncheckedIOException("读取收藏失败",e);}
    }
    private void commit(String owner,LinkedHashSet<String> values){
        try{AtomicJsonFiles.write(mapper,favoriteFile(owner),values);favorites.put(owner,values);}
        catch(IOException e){throw new UncheckedIOException("保存收藏失败，请重试",e);}
    }
    private Path publicFile(String id){return root.resolve("public").resolve(AtomicJsonFiles.key(id)+".json");}
    private Path favoriteFile(String owner){return root.resolve("favorites").resolve(AtomicJsonFiles.key(owner)+".json");}
}
