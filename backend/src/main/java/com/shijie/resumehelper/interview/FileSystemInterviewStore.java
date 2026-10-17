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
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
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
    public synchronized void save(InterviewSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (isDeleted(snapshot.owner(), snapshot.sessionId()))
            throw new InterviewException(410, "面试已删除");
        Path file = fileFor(snapshot.owner(), snapshot.sessionId());
        final Path parent = file.getParent();
        final Path temp;
        try {
            Files.createDirectories(parent);
            Path dirty = historyDirtyFor(snapshot.owner());
            if (!Files.exists(dirty)) Files.createFile(dirty);
            temp = Files.createTempFile(parent, "session-", ".tmp");
        } catch (IOException e) {
            throw storageFailure("会话存储失败", e);
        }
        boolean moved = false;
        try {
            mapper.writeValue(temp.toFile(), snapshot);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            try { upsertHistory(snapshot); }
            catch (RuntimeException indexFailure) {
                // Snapshot is authoritative. Keep the dirty marker so history rebuilds from snapshots.
            }
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
    public synchronized Optional<InterviewSnapshot> find(String owner, String sessionId) {
        if (isDeleted(owner, sessionId)) return Optional.empty();
        Path file = fileFor(owner, sessionId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.ofNullable(read(file, owner));
    }

    @Override
    public synchronized Optional<InterviewSnapshot> findByStartRequestId(String owner, String requestId) {
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
                if (owner.equals(snapshot.owner()) && requestId.equals(snapshot.startRequestId())
                        && !isDeleted(owner, snapshot.sessionId())) {
                    return Optional.of(snapshot);
                }
            }
        } catch (IOException e) {
            throw storageFailure("会话存储读取失败", e);
        }
        return Optional.empty();
    }

    @Override
    public synchronized void delete(String owner, String sessionId) {
        Path file = fileFor(owner, sessionId);
        if (!Files.exists(file)) return;
        InterviewSnapshot snapshot = read(file, owner);
        if (snapshot == null) return;
        Path marker = tombstoneFor(owner, sessionId);
        try {
            Files.createDirectories(marker.getParent());
            writeJsonAtomically(marker, new Tombstone(snapshot.expiresAt(), snapshot.startRequestId()));
            Files.deleteIfExists(file);
            removeHistory(owner, sessionId);
        } catch (IOException e) { throw storageFailure("会话删除失败", e); }
    }

    @Override
    public synchronized boolean isDeleted(String owner, String sessionId) {
        Path marker = tombstoneFor(owner, sessionId);
        if (!Files.isRegularFile(marker)) return false;
        try { return mapper.readValue(marker.toFile(), Tombstone.class).expiresAt().isAfter(Instant.now()); }
        catch (IOException | RuntimeException e) { throw storageFailure("删除标记损坏", e); }
    }

    @Override
    public synchronized HistoryPage list(String owner, int limit, String cursor, Instant now) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit 必须为 1–100");
        Objects.requireNonNull(now, "now");
        Instant cursorCreated = null;
        String cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            if (cursor.length() > 512) throw new IllegalArgumentException("分页游标无效");
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\n", 2);
                if (parts.length != 2) throw new IllegalArgumentException();
                cursorCreated = Instant.parse(parts[0]); cursorId = parts[1];
            } catch (RuntimeException invalid) { throw new IllegalArgumentException("分页游标无效"); }
        }
        List<HistoryItem> indexed = readOrBuildHistoryIndex(owner, now);
        Comparator<HistoryItem> order = Comparator.comparing(HistoryItem::createdAt).reversed()
                .thenComparing(HistoryItem::id, Comparator.reverseOrder());
        final Instant beforeCreated = cursorCreated;
        final String beforeId = cursorId;
        List<HistoryItem> items = new ArrayList<>(indexed.stream()
                .filter(item -> !item.expiresAt().isBefore(now) && !isDeleted(owner, item.id())).toList());
        items.sort(order);
        if (beforeCreated != null) {
            HistoryItem marker = new HistoryItem(beforeId, "", "", "", beforeCreated, beforeCreated, "", "");
            items.removeIf(item -> order.compare(item, marker) <= 0);
        }
        boolean more = items.size() > limit;
        List<HistoryItem> page = List.copyOf(items.subList(0, Math.min(limit, items.size())));
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                (page.get(page.size() - 1).createdAt() + "\n" + page.get(page.size() - 1).id()).getBytes(StandardCharsets.UTF_8)) : null;
        return new HistoryPage(page, next);
    }

    @Override
    public synchronized SessionCounts counts(String owner, Instant now) {
        List<HistoryItem> items = readOrBuildHistoryIndex(owner, now).stream()
                .filter(item -> !item.expiresAt().isBefore(now) && !isDeleted(owner, item.id())).toList();
        return new SessionCounts(items.stream().filter(item -> "ACTIVE".equals(item.status())).count(), items.size());
    }

    private record Tombstone(Instant expiresAt, String startRequestId) {}

    private Path historyIndexFor(String owner) { return SafePaths.resolveInside(ownerDir(owner), "history-index.json"); }
    private Path historyDirtyFor(String owner) { return SafePaths.resolveInside(ownerDir(owner), "history-index.dirty"); }

    private List<HistoryItem> readOrBuildHistoryIndex(String owner, Instant now) {
        Path index = historyIndexFor(owner);
        if (Files.isRegularFile(index) && !Files.exists(historyDirtyFor(owner))) {
            try { return mapper.readerForListOf(HistoryItem.class).readValue(index.toFile()); }
            catch (IOException | RuntimeException e) { throw storageFailure("历史索引损坏", e); }
        }
        Path dir = ownerDir(owner);
        if (!Files.isDirectory(dir)) return List.of();
        List<HistoryItem> items = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(FileSystemInterviewStore::isSnapshotFile)
                    .filter(p -> !p.getFileName().toString().equals("history-index.json")).toList()) {
                try {
                    InterviewSnapshot s = mapper.readValue(file.toFile(), InterviewSnapshot.class);
                    if (owner.equals(s.owner())) items.add(historyItem(s));
                } catch (IOException | RuntimeException ignored) { /* corrupt snapshots are not exposed in history */ }
            }
        } catch (IOException e) { throw storageFailure("历史索引初始化失败", e); }
        try {
            writeHistoryIndex(owner, items);
            Files.deleteIfExists(historyDirtyFor(owner));
        } catch (RuntimeException | IOException ignored) {
            // Return a fresh view even when the derived index cannot be repaired yet.
        }
        return List.copyOf(items);
    }

    private HistoryItem historyItem(InterviewSnapshot s) {
        String mode = s.plan() == null ? "FULL" : s.plan().mode().name();
        var p = s.progress();
        return new HistoryItem(s.sessionId(), s.targetRole(), mode, s.status(), s.createdAt(), s.expiresAt(),
                p.answeredRoots() + "/" + p.plannedRoots(), s.cachedReport() == null ? "NOT_READY" : "READY");
    }

    private void upsertHistory(InterviewSnapshot snapshot) {
        List<HistoryItem> items = new ArrayList<>(readHistoryIndexForUpdate(snapshot.owner()));
        items.removeIf(item -> item.id().equals(snapshot.sessionId()));
        items.add(historyItem(snapshot));
        writeHistoryIndex(snapshot.owner(), items);
        try { Files.deleteIfExists(historyDirtyFor(snapshot.owner())); }
        catch (IOException e) { throw storageFailure("历史索引状态更新失败", e); }
    }

    private List<HistoryItem> readHistoryIndexForUpdate(String owner) {
        Path index = historyIndexFor(owner);
        if (!Files.exists(historyDirtyFor(owner)) && Files.isRegularFile(index)) {
            try { return mapper.readerForListOf(HistoryItem.class).readValue(index.toFile()); }
            catch (IOException | RuntimeException e) { throw storageFailure("历史索引损坏", e); }
        }
        return scanHistory(owner);
    }

    private List<HistoryItem> scanHistory(String owner) {
        Path dir = ownerDir(owner);
        if (!Files.isDirectory(dir)) return List.of();
        List<HistoryItem> items = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(FileSystemInterviewStore::isSnapshotFile)
                    .filter(p -> !p.getFileName().toString().equals("history-index.json")).toList()) {
                try {
                    InterviewSnapshot s = mapper.readValue(file.toFile(), InterviewSnapshot.class);
                    if (owner.equals(s.owner())) items.add(historyItem(s));
                } catch (IOException | RuntimeException ignored) { }
            }
        } catch (IOException e) { throw storageFailure("历史索引初始化失败", e); }
        return items;
    }

    private void removeHistory(String owner, String sessionId) {
        List<HistoryItem> items = new ArrayList<>(readOrBuildHistoryIndex(owner, Instant.MIN));
        if (items.removeIf(item -> item.id().equals(sessionId))) writeHistoryIndex(owner, items);
    }

    private void writeHistoryIndex(String owner, List<HistoryItem> items) {
        Path index = historyIndexFor(owner), temp = null;
        try {
            Files.createDirectories(index.getParent());
            temp = Files.createTempFile(index.getParent(), "history-", ".tmp");
            mapper.writeValue(temp.toFile(), items);
            Files.move(temp, index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw storageFailure("历史索引写入失败", e); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { } }
    }

    private void writeJsonAtomically(Path target, Object value) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), "deleted-", ".tmp");
        try {
            mapper.writeValue(temp.toFile(), value);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw storageFailure("当前环境不支持原子删除标记，未删除会话", unsupported);
        } finally { Files.deleteIfExists(temp); }
    }

    @Override
    public List<InterviewSnapshot> findBySource(String owner, InterviewPlan.ResumeSource source) {
        Path dir = ownerDir(owner);
        if (!Files.isDirectory(dir)) return List.of();
        List<InterviewSnapshot> matches = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(FileSystemInterviewStore::isSnapshotFile).toList()) {
                try {
                    InterviewSnapshot snapshot = mapper.readValue(file.toFile(), InterviewSnapshot.class);
                    if (!owner.equals(snapshot.owner()) || snapshot.plan() == null
                            || !source.equals(snapshot.plan().source())) continue;
                    matches.add(snapshot);
                } catch (IOException | RuntimeException unreadable) {
                    // Ignore unrelated or damaged snapshots; the selected session remains readable by ID.
                }
            }
        } catch (IOException e) { throw storageFailure("会话存储读取失败", e); }
        return List.copyOf(matches);
    }

    @Override
    public void savePlan(String owner, InterviewPlanService.StoredPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!owner.equals(plan.owner())) throw new IllegalArgumentException("计划 owner 与目录不一致");
        Path file = planFile(owner, plan.preview().planId());
        Path parent = file.getParent();
        Path temp;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, "plan-", ".tmp");
        } catch (IOException e) { throw storageFailure("计划存储失败", e); }
        boolean moved = false;
        try {
            mapper.writeValue(temp.toFile(), plan);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } catch (AtomicMoveNotSupportedException e) {
            throw storageFailure("当前环境不支持原子写入，计划未保存", e);
        } catch (IOException e) { throw storageFailure("计划存储失败", e); }
        finally {
            if (!moved) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    @Override
    public Optional<InterviewPlanService.StoredPlan> findPlan(String owner, String planId) {
        Path file = planFile(owner, planId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        var stored = readPlan(file);
        return owner.equals(stored.owner()) && planId.equals(stored.preview().planId())
                ? Optional.of(stored) : Optional.empty();
    }

    @Override
    public Optional<InterviewPlanService.StoredPlan> findPlanByRequestId(String owner, String requestId) {
        if (requestId == null) return Optional.empty();
        Path dir = planDir(owner);
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                InterviewPlanService.StoredPlan stored;
                try { stored = mapper.readValue(file.toFile(), InterviewPlanService.StoredPlan.class); }
                catch (IOException | RuntimeException unreadable) { continue; }
                if (owner.equals(stored.owner()) && requestId.equals(stored.requestId())) return Optional.of(stored);
            }
        } catch (IOException e) { throw storageFailure("计划存储读取失败", e); }
        return Optional.empty();
    }

    @Override
    public int deleteExpired(Instant now) {
        return deleteExpired(now, 100);
    }

    @Override
    public synchronized int deleteExpired(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (!Files.isDirectory(storageDir)) {
            return 0;
        }
        int removed = 0;
        try (var owners = Files.list(storageDir)) {
            for (Path ownerDir : owners.filter(Files::isDirectory).toList()) {
                try (var files = Files.list(ownerDir)) {
                    for (Path file : files.filter(FileSystemInterviewStore::isSnapshotFile).toList()) {
                        try {
                            if (file.getFileName().toString().endsWith(".deleted.json")) {
                                Tombstone marker = mapper.readValue(file.toFile(), Tombstone.class);
                                if (marker.expiresAt().isBefore(now) && Files.deleteIfExists(file)) {
                                    removed++;
                                    if (removed >= limit) return removed;
                                }
                                continue;
                            }
                            if (file.getFileName().toString().equals("history-index.json")) continue;
                            var snapshot = mapper.readValue(file.toFile(), InterviewSnapshot.class);
                            if (snapshot.expiresAt().isBefore(now) && Files.deleteIfExists(file)) {
                                removeHistory(snapshot.owner(), snapshot.sessionId());
                                Files.deleteIfExists(tombstoneFor(snapshot.owner(), snapshot.sessionId()));
                                removed++;
                                if (removed >= limit) return removed;
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

    private Path tombstoneFor(String owner, String sessionId) {
        Path file = fileFor(owner, sessionId);
        return file.resolveSibling(sessionId + ".deleted.json");
    }

    private Path ownerDir(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner 不能为空");
        }
        return SafePaths.resolveInside(storageDir, SafePaths.safeSegment(owner, "anonymous"));
    }

    private Path planDir(String owner) {
        return SafePaths.resolveInside(ownerDir(owner), "plans");
    }

    private Path planFile(String owner, String planId) {
        if (planId == null || !SESSION_ID.matcher(planId).matches()
                || ".".equals(planId) || "..".equals(planId)) throw new IllegalArgumentException("非法计划 ID");
        return SafePaths.resolveInside(planDir(owner), planId + ".json");
    }

    private InterviewPlanService.StoredPlan readPlan(Path file) {
        try { return mapper.readValue(file.toFile(), InterviewPlanService.StoredPlan.class); }
        catch (IOException | RuntimeException e) { throw storageFailure("计划数据损坏或版本不支持", e); }
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
