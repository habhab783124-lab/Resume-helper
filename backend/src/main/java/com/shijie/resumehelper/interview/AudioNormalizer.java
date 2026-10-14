package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 把浏览器录音统一成 ASR 要求的 16 kHz 单声道 PCM16 WAV。
 *
 * <p>浏览器用 {@code MediaRecorder} 录出来的是 WebM/Opus（Chrome/Edge）或 MP4/AAC（Safari），
 * 封装和采样率都不能信，改扩展名没用，必须真的转一次。放在后端而不是前端，
 * 是为了不用在前端维护重采样器和编码器去对齐跨浏览器差异。
 *
 * <p>子进程只读临时目录里的本地文件，并显式限制协议白名单，
 * 避免被输入内容诱导去访问网络或其他路径。
 */
@Service
public class AudioNormalizer {

    private static final Logger log = LoggerFactory.getLogger(AudioNormalizer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROTOCOL_WHITELIST = "file,pipe";
    private static final String OVERSIZE = "录音文件超过大小限制，请缩短后重新录音";
    private static final String UNREADABLE = "无法读取录音格式，请重新录音";
    private static final String TIMEOUT = "录音转换超时，请缩短录音后重试";
    /** 16 kHz 单声道 16 bit，每秒 32000 字节。 */
    private static final long BYTES_PER_SECOND = 16000 * 2;
    private static final long MAX_TOOL_STDOUT_BYTES = 1024 * 1024;
    private static final long MAX_AUDIO_OUTPUT_BYTES = 5L * 1024 * 1024;

    /** 规范化后的音频。只暴露字节，不把临时路径泄露给上层。 */
    public record NormalizedAudio(byte[] wav, long durationMs) {}

    private final SpeechProperties properties;

    public AudioNormalizer(SpeechProperties properties) {
        this.properties = properties;
    }

    public NormalizedAudio normalize(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InterviewException(400, "录音内容为空");
        }
        if (file.getSize() > properties.getMaxAudioBytes()) {
            throw new InterviewException(413, OVERSIZE);
        }

        Path dir = null;
        try {
            dir = Files.createTempDirectory("interview-audio-");
            Path inputFile = dir.resolve("input.bin");
            Path outputFile = dir.resolve("output.wav");
            copyBounded(file, inputFile);

            long maxMs = (long) properties.getMaxDurationSeconds() * 1000;

            // 容器里读得到时长就先拦，省得白转一次长音频。
            // 读不到时长不能拦：MediaRecorder 的 WebM 流式封装就没有 Duration 元素，
            // 那种情况靠下面对转码产物（定长 PCM）的测量兜底。
            long declaredMs = declaredDurationMs(inputFile, dir.resolve("ffprobe.out"),
                    dir.resolve("ffprobe.err"));
            if (declaredMs > 0 && declaredMs > maxMs) {
                throw new InterviewException(422, tooLong(maxMs));
            }

            transcode(inputFile, outputFile, dir.resolve("ffmpeg.err"));

            byte[] wav = Files.readAllBytes(outputFile);
            if (!isWav(wav)) {
                throw new InterviewException(415, UNREADABLE);
            }
            // 时长判断必须先于大小上限：产物是 16k 单声道 PCM16，每多 1 秒 32 KB，
            // 超长的录音很容易先撞上大小上限。那种情况用户需要的是"缩短录音"，
            // 而不是"格式不对请重录"。
            long durationMs = wavDurationMs(wav);
            if (durationMs > maxMs) {
                throw new InterviewException(422, tooLong(maxMs));
            }
            if (wav.length > MAX_AUDIO_OUTPUT_BYTES) {
                log.warn("规范化音频 {} 字节超过 {} 上限", wav.length, MAX_AUDIO_OUTPUT_BYTES);
                throw new InterviewException(415, UNREADABLE);
            }
            return new NormalizedAudio(wav, durationMs);
        } catch (IOException e) {
            log.error("录音规范化失败：{}", e.getMessage());
            throw new InterviewException(500, "录音处理失败，请重试");
        } finally {
            deleteRecursively(dir);
        }
    }

    private String tooLong(long maxMs) {
        return "录音超过 " + (maxMs / 1000) + " 秒，请缩短后重新录音";
    }

    // ------------------------------------------------------------------
    // FFprobe
    // ------------------------------------------------------------------

    /**
     * 返回容器里声明的时长；读不到时返回 -1，交给转码后的定长 PCM 测量兜底。
     * 只有两种情况在这里直接失败：ffprobe 根本没跑起来（503，见 {@link #runTool}），
     * 或文件里确实没有音轨（415）。
     *
     * <p>ffprobe 报不出结构不当作格式错误：ffmpeg 的容错比 ffprobe 高，
     * 真解不了的文件会在转码阶段以 415 被拦下，错误码一样，但不会把
     * "ffprobe 解析不了"和"根本没有音轨"混成一个结论。
     */
    private long declaredDurationMs(Path input, Path stdout, Path stderr) {
        // 注意：ffprobe 没有 -nostdin 和 -hide_banner，传了会直接报错。
        List<String> args = List.of(properties.getFfprobePath(),
                "-v", "error",
                "-protocol_whitelist", PROTOCOL_WHITELIST,
                "-show_format", "-show_streams",
                "-show_entries", "format=duration,stream=codec_type",
                "-of", "json",
                input.toString());
        int exit = runTool(stdout, stderr, args);
        JsonNode root = readTree(exit == 0 ? readFile(stdout) : "");
        if (root == null) {
            log.warn("ffprobe 未能解析出结构 exit={} input={} stderr={}", exit, input.getFileName(), tail(stderr));
            return -1;
        }
        boolean hasAudio = false;
        for (JsonNode stream : root.path("streams")) {
            if ("audio".equals(stream.path("codec_type").asText())) {
                hasAudio = true;
                break;
            }
        }
        if (!hasAudio) {
            log.warn("录音里没有音轨 container={}", root.path("format").path("format_name").asText());
            throw new InterviewException(415, UNREADABLE);
        }
        return declaredDurationMs(root);
    }

    private long declaredDurationMs(JsonNode root) {
        String raw = root.path("format").path("duration").asText("");
        if (raw.isEmpty()) {
            return -1;
        }
        try {
            double seconds = Double.parseDouble(raw);
            if (seconds <= 0 || Double.isInfinite(seconds) || Double.isNaN(seconds)) {
                return -1;   // "N/A"、0、Infinity 都算读不到
            }
            return (long) (seconds * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // FFmpeg
    // ------------------------------------------------------------------

    /**
     * 刻意不传 {@code -t}：录音超长必须报错让用户缩短，
     * 不能靠 FFmpeg 默默截断成 120 秒再送识别——那会让用户以为长回答已经提交了。
     */
    private void transcode(Path input, Path output, Path stderr) {
        List<String> args = List.of(properties.getFfmpegPath(),
                "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-protocol_whitelist", PROTOCOL_WHITELIST,
                "-i", input.toString(),
                "-map", "0:a:0", "-vn", "-ac", "1", "-ar", "16000",
                "-c:a", "pcm_s16le",
                output.toString());
        int exit = runTool(null, stderr, args);
        if (exit != 0) {
            log.warn("录音转码失败 exit={} stderr={}", exit, tail(stderr));
            throw new InterviewException(415, UNREADABLE);
        }
    }

    /**
     * stdout / stderr 都重定向到文件而不是 pipe：pipe 只有几十 KB，
     * 工具一旦写满就会阻塞，超时检测也就形同虚设。
     *
     * @param stdout null 表示不关心 stdout（转码阶段用）
     * @return 退出码；超时与被中断抛 504
     */
    private int runTool(Path stdout, Path stderr, List<String> args) {
        Process process = null;
        try {
            process = new ProcessBuilder(args)
                    .redirectOutput(stdout == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(stdout.toFile()))
                    .redirectError(ProcessBuilder.Redirect.to(stderr.toFile()))
                    .start();
            if (!process.waitFor(properties.getConversionTimeoutSeconds(), TimeUnit.SECONDS)) {
                destroyAndAwait(process);
                log.warn("音频工具超时 command={}", args.get(0));
                throw new InterviewException(504, TIMEOUT);
            }
            if (stdout != null && Files.size(stdout) > MAX_TOOL_STDOUT_BYTES) {
                return -1;
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            destroyAndAwait(process);
            Thread.currentThread().interrupt();
            throw new InterviewException(504, TIMEOUT);
        } catch (IOException e) {
            log.warn("音频工具输出读取失败：{}", e.getMessage());
            return -1;
        }
    }

    private static void destroyAndAwait(Process process) {
        if (process == null) {
            return;
        }
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // 文件与字节
    // ------------------------------------------------------------------

    private void copyBounded(MultipartFile file, Path destination) {
        long limit = properties.getMaxAudioBytes();
        try (InputStream in = file.getInputStream();
             OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                // getSize() 可能报不准，真正以实际读到的字节为准。
                if (total > limit) {
                    throw new InterviewException(413, OVERSIZE);
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new InterviewException(400, "录音文件读取失败，请重新录音");
        }
    }

    private static boolean isWav(byte[] wav) {
        return wav.length >= 12
                && Arrays.equals("RIFF".getBytes(StandardCharsets.US_ASCII), Arrays.copyOfRange(wav, 0, 4))
                && Arrays.equals("WAVE".getBytes(StandardCharsets.US_ASCII), Arrays.copyOfRange(wav, 8, 12));
    }

    /** 产物是定长 PCM，直接用 data chunk 长度算时长，比 ffprobe 更可靠。 */
    private static long wavDurationMs(byte[] wav) {
        long dataBytes = wavDataChunkBytes(wav);
        return dataBytes > 0 ? dataBytes * 1000 / BYTES_PER_SECOND : 0;
    }

    /** 遍历 RIFF chunk 取 data 段长度；找不到就退回「文件大小减 44」。 */
    private static long wavDataChunkBytes(byte[] wav) {
        for (int offset = 12; offset + 8 <= wav.length; offset += 8) {
            String id = new String(wav, offset, 4, StandardCharsets.US_ASCII);
            int size = readInt32Little(wav, offset + 4);
            if (size <= 0) {
                break;
            }
            if ("data".equals(id)) {
                return size;
            }
            offset += size;
        }
        return Math.max(0, wav.length - 44L);
    }

    private static int readInt32Little(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private JsonNode readTree(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            log.warn("ffprobe 输出不是合法 JSON：{}", e.getMessage());
            return null;
        }
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    private static String tail(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() > 512 ? text.substring(text.length() - 512) : text;
        } catch (IOException e) {
            return "(无法读取工具错误输出)";
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> all = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : all) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // 清理失败不能影响主流程，但不能静默——临时文件会一直留在磁盘上。
            log.error("音频临时目录清理失败 {}：{}", dir, e.getMessage());
        }
    }
}
