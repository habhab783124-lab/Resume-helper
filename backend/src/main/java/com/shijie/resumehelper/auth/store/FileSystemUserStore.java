package com.shijie.resumehelper.auth.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.auth.config.AuthProperties;
import com.shijie.resumehelper.auth.domain.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 账号的文件系统实现：{@code {storageDir}/users.json}。
 *
 * <p>课程项目是单机单实例，一个 JSON 文件足够用；写入时先写临时文件再原子替换，
 * 避免进程中断留下半个文件。多实例部署前应迁移到数据库。
 */
public class FileSystemUserStore implements UserStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemUserStore.class);
    private static final String USERS_FILE = "users.json";

    private final Path file;
    private final ObjectMapper mapper;
    private final Map<String, UserAccount> users = new LinkedHashMap<>();
    private final Object lock = new Object();
    private boolean loaded;

    public FileSystemUserStore(AuthProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.file = Path.of(properties.getStorageDir()).toAbsolutePath().normalize().resolve(USERS_FILE);
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
        String normalized = normalizeEmail(email);
        synchronized (lock) {
            load();
            return users.values().stream()
                    .filter(user -> user.email().equals(normalized))
                    .findFirst();
        }
    }

    @Override
    public Optional<UserAccount> findById(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        synchronized (lock) {
            load();
            return Optional.ofNullable(users.get(userId));
        }
    }

    @Override
    public boolean create(UserAccount account) {
        String normalized = normalizeEmail(account.email());
        synchronized (lock) {
            load();
            boolean exists = users.values().stream().anyMatch(user -> user.email().equals(normalized));
            if (exists) {
                return false;
            }
            users.put(account.id(), account);
            try {
                persist();
            } catch (RuntimeException e) {
                users.remove(account.id());
                throw e;
            }
            return true;
        }
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            UserFile stored = mapper.readValue(file.toFile(), UserFile.class);
            if (stored != null && stored.users() != null) {
                for (UserAccount user : stored.users()) {
                    if (user != null && user.id() != null && user.email() != null) {
                        users.put(user.id(), user);
                    }
                }
            }
        } catch (IOException e) {
            // 账号文件损坏时不能静默当成空库，否则后续注册会覆盖掉所有账号。
            throw new IllegalStateException("读取账号文件失败：" + file, e);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "users-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temp.toFile(), new UserFile(new ArrayList<>(users.values())));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("保存账号文件失败：" + file, e);
        }
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** JSON 文件的外层结构，方便以后加版本号或迁移字段。 */
    private record UserFile(List<UserAccount> users) {
    }
}
