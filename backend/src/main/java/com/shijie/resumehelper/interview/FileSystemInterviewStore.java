package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.util.SafePaths;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 以 {@code storage-dir / 安全用户目录 / sessionId.json} 存放快照的文件实现。
 *
 * <p>写入走「同目录临时文件 + 原子替换」：故障写入不损坏旧记录；文件系统不支持
 * 原子替换时明确失败并清理临时文件，不先发布新内存状态。
 *
 * <p>路径安全复用 {@link SafePaths} 的两层校验：owner 经 {@code safeSegment} 清洗，
 * 拼完再确认落在存储目录之内；内容里的 owner 与目录不一致时也拒绝返回。
 */
public final class FileSystemInterviewStore implements InterviewStore {

    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final Path storageDir;
    private final ObjectMapper mapper;

    public FileSystemInterviewStore(Path storageDir, ObjectMapper mapper) {
        this.storageDir = Objects.requireNonNull(storageDir, "storageDir");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void save(InterviewSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Path file = fileFor(snapshot.owner(), snapshot.sessionId());
        final Path parent = file.getParent();
        final Path temp;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, "session-", ".tmp");
        } catch (IOException e) {
            throw storageFailure("会话存储失败", e);
        }
        boolean moved = false;
        try {
            mapper.writeValue(temp.toFile(), snapshot);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } catch (AtomicMoveNotSupportedException e) {
            throw storageFailure("当前环境不支持原子写入，会话未保存", e);
        } catch (IOException e) {
            throw storageFailure("会话存储失败", e);
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不掩盖原始错误
                }
            }
        }
    }

    @Override
    public Optional<InterviewSnapshot> find(String owner, String sessionId) {
        Path file = fileFor(owner, sessionId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.ofNullable(read(file, owner));
    }

    @Override
    public Optional<InterviewSnapshot> findByStartRequestId(String owner, String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        Path dir = ownerDir(owner);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(FileSystemInterviewStore::isSnapshotFile).toList()) {
                InterviewSnapshot snapshot;
                try {
                    snapshot = mapper.readValue(file.toFile(), InterviewSnapshot.class);
                } catch (IOException | RuntimeException unreadable) {
                    continue; // 单个损坏文件不阻塞幂等查询，读取它时才报受控错误
                }
                if (owner.equals(snapshot.owner()) && requestId.equals(snapshot.startRequestId())) {
                    return Optional.of(snapshot);
                }
            }
        } catch (IOException e) {
            throw storageFailure("会话存储读取失败", e);
        }
        return Optional.empty();
    }

    @Override
    public int deleteExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!Files.isDirectory(storageDir)) {
            return 0;
        }
        int removed = 0;
        try (var owners = Files.list(storageDir)) {
            for (Path ownerDir : owners.filter(Files::isDirectory).toList()) {
                try (var files = Files.list(ownerDir)) {
                    for (Path file : files.filter(FileSystemInterviewStore::isSnapshotFile).toList()) {
                        try {
                            var snapshot = mapper.readValue(file.toFile(), InterviewSnapshot.class);
                            if (snapshot.expiresAt().isBefore(now) && Files.deleteIfExists(file)) {
                                removed++;
                            }
                        } catch (IOException | RuntimeException unreadable) {
                            // 损坏/未知版本文件跳过，保留给受控读取报错，不在清理时猜测
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw storageFailure("会话存储清理失败", e);
        }
        return removed;
    }

    /** 读取并做内容级 owner 校验；owner 不匹配返回 {@code null}（视为不存在）。 */
    private InterviewSnapshot read(Path file, String owner) {
        final InterviewSnapshot snapshot;
        try {
            snapshot = mapper.readValue(file.toFile(), InterviewSnapshot.class);
        } catch (IOException | RuntimeException e) {
            // 含未知 schemaVersion：快照构造器抛出的参数错误被 Jackson 包成 JsonMappingException
            throw storageFailure("会话数据损坏或版本不支持", e);
        }
        return owner.equals(snapshot.owner()) ? snapshot : null;
    }

    private Path fileFor(String owner, String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()
                || ".".equals(sessionId) || "..".equals(sessionId)) {
            throw new IllegalArgumentException("非法会话 ID");
        }
        return SafePaths.resolveInside(ownerDir(owner), sessionId + ".json");
    }

    private Path ownerDir(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner 不能为空");
        }
        return SafePaths.resolveInside(storageDir, SafePaths.safeSegment(owner, "anonymous"));
    }

    private static boolean isSnapshotFile(Path file) {
        return file.getFileName().toString().endsWith(".json");
    }

    private static InterviewException storageFailure(String message, Throwable cause) {
        var failure = new InterviewFailure(InterviewFailure.Code.STORAGE, false, "会话存储失败", null);
        var exception = new InterviewException(500, message, failure);
        exception.initCause(cause);
        return exception;
    }
}
