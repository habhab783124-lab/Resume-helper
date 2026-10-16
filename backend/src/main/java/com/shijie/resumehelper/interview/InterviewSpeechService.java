package com.shijie.resumehelper.interview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 语音编排层：把火山调用接回带会话归属的面试状态。
 *
 * <p>职责只有三件——校验会话归属与当前题、限制并发、按顺序调用
 * {@link AudioNormalizer} 与 {@link SpeechClient}。面试状态仍然只由 {@link InterviewService} 管理，
 * 本类不持有任何面试状态锁等待网络返回：火山调用前后各做一次无锁校验，
 * 结果和当前题目对不上就按 409 丢弃，前端重新同步。
 *
 * <p>转写只产出草稿，不调用 {@link InterviewService#answer}——
 * 用户确认后才走现有 turns 接口提交，避免一次识别就推进一轮。
 */
@Service
public class InterviewSpeechService {
    private static final Logger log = LoggerFactory.getLogger(InterviewSpeechService.class);

    private static final String BUSY = "语音服务繁忙，请稍后再试";
    private static final String INVALID_REQUEST = "请求参数不正确，请重新提交";
    private static final String STATE_CHANGED = "面试状态已变化，请同步当前题目后继续";
    private static final String UNKNOWN_QUESTION = "题目不存在，请刷新面试记录";

    /** 连通性检查的合成文本。短，避免一次测试花掉明显的额度。 */
    private static final String CHECK_TEXT = "语音配置测试";

    /** 本机语音能力开关与本项目限制。只代表配置完整和转码工具可执行，不宣称账户额度可用。 */
    public record Capabilities(boolean asrEnabled, boolean ttsEnabled,
                               int maxRecordingSeconds, int maxAudioBytes) {}

    /** 转写草稿；字段回带原值，供前端校验结果和本次操作确实对应。 */
    public record Transcription(String sessionId, String questionId, String requestId,
                                String text, long durationMs) {}

    private final InterviewService interviews;
    private final AudioNormalizer normalizer;
    private final SpeechProperties properties;

    /** 全局语音并发闸门；同一个 owner 的「最多 1 个」由 {@link #busyOwners} 单独限制。 */
    private final Semaphore permits;
    private final Set<String> busyOwners = ConcurrentHashMap.newKeySet();

    /** 连通性检查结果：能返回这个对象就说明本次实际发出的合成请求拿到了音频。 */
    public record SpeechCheck(String speaker, long audioBytes) {}

    public InterviewSpeechService(InterviewService interviews, AudioNormalizer normalizer,
                                  SpeechProperties properties) {
        this.interviews = interviews;
        this.normalizer = normalizer;
        this.properties = properties;
        // 配成 0 或负数会让 tryAcquire 永远失败，语音变成永久 429，不如退回 1。
        this.permits = new Semaphore(Math.max(1, properties.getMaxConcurrentRequests()));
    }

    /**
     * 能力判断必须按<b>本次调用实际使用的密钥</b>算，而不是服务端配置。
     *
     * <p>前端填了自己的 key 之后，这里如果还只看服务端配置，就会出现
     * 「设置页测试连接成功，面试页却显示语音不可用」这种自相矛盾的反馈。
     */
    public Capabilities capabilities(String apiKey) {
        boolean keyReady = notBlank(apiKey);
        boolean toolsReady = toolAvailable(properties.getFfmpegPath()) && toolAvailable(properties.getFfprobePath());
        return new Capabilities(
                keyReady && toolsReady && notBlank(properties.getAsrResourceId()),
                keyReady && notBlank(properties.getTtsResourceId()) && notBlank(properties.getTtsSpeaker()),
                properties.getMaxDurationSeconds(),
                properties.getMaxAudioBytes());
    }

    public Transcription transcribe(String owner, String sessionId, String questionId,
                                    String requestId, MultipartFile file, SpeechClient speech) {
        requireUuid(requestId);
        requireAnswerable(owner, sessionId, questionId);
        return withPermit(owner, () -> {
            AudioNormalizer.NormalizedAudio audio = normalizer.normalize(file);
            SpeechClient.Transcript transcript = speech.transcribe(audio.wav(), requestId);
            // 识别期间用户可能已经切题、答完或结束：结果和当前题目对不上就不能当有效草稿。
            requireAnswerable(owner, sessionId, questionId);
            return new Transcription(sessionId, questionId, requestId, transcript.text(), audio.durationMs());
        });
    }

    public SpeechClient.SpeechAudio synthesize(String owner, String sessionId, String questionId,
                                               SpeechClient speech) {
        // 只朗读服务器已经生成的题目文字，题目列表里没有就拒绝，
        // 这样浏览器没法借这个接口朗读任意文本。答过或结束后的题仍可重播，方便回看。
        InterviewService.Session session = interviews.get(owner, sessionId);
        InterviewService.Turn turn = session.turns().stream()
                .filter(t -> questionId.equals(t.questionId()))
                .findFirst()
                .orElseThrow(() -> new InterviewException(409, UNKNOWN_QUESTION));
        return withPermit(owner, () -> speech.synthesize(turn.question(), UUID.randomUUID().toString()));
    }

    /**
     * 设置页的「测试连接」用：真实合成一句固定短文本。
     *
     * <p>为什么用 TTS 而不是 ASR：ASR 需要一段真实录音才能验证，
     * 空载调用只会得到「无有效语音」的假失败。TTS 和 ASR 共用同一把 key、
     * 走同一套鉴权；此检测只确认 TTS 资源授权，ASR 资源仍需真实录音验证。
     *
     * <p>不复用 {@link #synthesize}：那个接口朗读的是面试题目文字，
     * 连通性检查不需要会话上下文，也不该为了让它成功而要求用户先开一场面试。
     * 走 {@link #withPermit} 是为了同样受并发闸门约束——测试也花钱。
     */
    public SpeechCheck check(String owner, SpeechClient speech) {
        Supplier<SpeechClient.SpeechAudio> synthesize =
                () -> speech.synthesize(CHECK_TEXT, UUID.randomUUID().toString());
        SpeechClient.SpeechAudio audio = withPermit(owner, synthesize);
        return new SpeechCheck(properties.getTtsSpeaker(), audio.bytes().length);
    }

    /** 只有本人、ACTIVE、题目就是当前题、且没有待重试回答时才允许转写。 */
    private void requireAnswerable(String owner, String sessionId, String questionId) {
        InterviewService.Session session = interviews.get(owner, sessionId);
        if (!"ACTIVE".equals(session.status()) || session.pendingAnswer() != null
                || session.currentQuestion() == null
                || !questionId.equals(session.currentQuestion().questionId())) {
            log.info("语音转写被拒绝 sessionId={} status={} pending={} current={} requested={}",
                    sessionId, session.status(), session.pendingAnswer() != null,
                    session.currentQuestion() == null ? null : session.currentQuestion().questionId(),
                    questionId);
            throw new InterviewException(409, STATE_CHANGED);
        }
    }

    /**
     * 同一个 owner 的语音请求串行执行，全局并发受 {@code max-concurrent-requests} 限制，
     * 拿不到就立刻 429——语音调用会真的花钱，排队等待只会让用户以为系统卡死。
     */
    private <T> T withPermit(String owner, Supplier<T> work) {
        if (!busyOwners.add(owner)) {
            throw new InterviewException(429, BUSY);
        }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire();
            if (!acquired) {
                throw new InterviewException(429, BUSY);
            }
            return work.get();
        } finally {
            // owner 标记在获取全局许可前就加入，获取失败不能释放不存在的许可。
            if (acquired) permits.release();
            busyOwners.remove(owner);
        }
    }

    private static void requireUuid(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new InterviewException(400, INVALID_REQUEST);
        }
        try {
            UUID.fromString(requestId);
        } catch (IllegalArgumentException e) {
            throw new InterviewException(400, INVALID_REQUEST);
        }
    }

    /**
     * 相对命令名要在 PATH 里逐个目录找；绝对路径或带分隔符的路径直接判断。
     *
     * <h2>Windows 上必须补扩展名</h2>
     * Windows 的 PATH 里可执行文件是带扩展名的（{@code ffmpeg.exe}），
     * 而配置里写的是命令名（{@code ffmpeg}）。
     * 只查 {@code Path.of(dir, "ffmpeg")} 在 Windows 上<b>永远找不到</b>，
     * 结果是：明明装了 FFmpeg，能力接口却报告语音关闭——而且不报错，只显示"不可用"，
     * 排查起来很费时间（这正是"沉默失败"，最难查的那类）。
     *
     * <p>这段原来是 macOS/Linux 上开发的（那边文件名就是 {@code ffmpeg}，不带扩展名），
     * 所以在 Windows 上一直没暴露。部署到 Windows 时才会踩到。
     */
    private static boolean toolAvailable(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        if (Files.isExecutable(Path.of(command))) {
            return true;
        }
        if (command.contains("/") || command.contains("\\")) {
            return false;
        }
        String pathVariable = System.getenv("PATH");
        if (pathVariable == null) {
            return false;
        }
        for (String dir : pathVariable.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            if (Files.isExecutable(Path.of(dir, command))) {
                return true;
            }
            for (String extension : WINDOWS_EXECUTABLE_EXTENSIONS) {
                if (Files.isExecutable(Path.of(dir, command + extension))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Windows 上按命令名找可执行文件时要试的扩展名。
     *
     * <p>在其它系统上是空数组——不加扩展名去查反而可能误命中一个无关文件。
     */
    private static final String[] WINDOWS_EXECUTABLE_EXTENSIONS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? new String[]{".exe", ".cmd", ".bat"}
                    : new String[0];

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
