package com.shijie.resumehelper.job;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.store.FileSystemJobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class JobSnapshotStoreTest {
    @TempDir Path directory;
    FileSystemJobStore store(){return new FileSystemJobStore(directory.toString(),new ObjectMapper().findAndRegisterModules());}
    @Test void snapshotsAndOwnerFavoritesSurviveRestartBeyondSearchLimit(){
        var first=store();
        for(int i=0;i<500;i++)first.put(Job.builder().id("job-"+i).title("开发"+i).build());
        assertTrue(first.save("alice","job-400"));
        assertFalse(first.save("alice","job-400"));
        var reopened=store();
        assertTrue(reopened.find("job-400").isPresent());
        assertEquals(1,reopened.saved("alice").size());
        assertFalse(reopened.isSaved("bob","job-400"));
        assertTrue(reopened.unsave("alice","job-400"));
        assertTrue(store().saved("alice").isEmpty());
    }
    @Test void privateImportedTextNeverEntersPublicSnapshotStore(){
        store().put(Job.builder().id("imported-secret").title("private").source("手动导入","").build());
        assertTrue(store().find("imported-secret").isEmpty());
    }
}
