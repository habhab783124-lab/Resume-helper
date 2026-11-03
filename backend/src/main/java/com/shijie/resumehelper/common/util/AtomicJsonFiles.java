package com.shijie.resumehelper.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Single-instance file repositories: safe names and replace-on-success writes. */
public final class AtomicJsonFiles {
    private AtomicJsonFiles() {}
    public static String key(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("标识不能为空");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static void write(ObjectMapper mapper, Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary=Files.createTempFile(file.getParent(), "write-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), value);
            try { Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
