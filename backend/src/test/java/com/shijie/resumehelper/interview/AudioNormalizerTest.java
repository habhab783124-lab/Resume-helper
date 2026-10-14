package com.shijie.resumehelper.interview;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 用真实 FFmpeg / FFprobe 跑规范化。测试前确认工具可执行，
 * 避免"部署机器没装 ffmpeg"以一次沉默通过的形式溜过去。
 */
class AudioNormalizerTest {

    private static final String TMP_PREFIX = "interview-audio-";

    @BeforeAll
    static void realToolsAreInstalled() {
        runTool("ffmpeg", "-version");
        runTool("ffprobe", "-version");
    }

    @Test
    void transcodesBrowserRecordingToPcm16Mono16k() throws Exception {
        Path webm = fixture("recording.webm", "sine=frequency=440:duration=5",
                List.of("-c:a", "libopus", "-b:a", "64k"));
        try {
            var audio = normalize(props(), mockFile("recording.webm", "audio/webm", Files.readAllBytes(webm)));

            assertArrayEquals("RIFF".getBytes(StandardCharsets.US_ASCII), java.util.Arrays.copyOf(audio.wav(), 4));
            assertArrayEquals("WAVE".getBytes(StandardCharsets.US_ASCII), java.util.Arrays.copyOfRange(audio.wav(), 8, 12));
            assertTrue(audio.wav().length > 50_000, "5 秒 16k PCM16 应远超 44 字节的头");
            assertEquals(5000L, audio.durationMs(), 250L);

            // 再交给 ffprobe 验一次真实编码，别只信字节数学。
            Path wav = Files.createTempFile("audio-normalizer-check-", ".wav");
            try {
                Files.write(wav, audio.wav());
                String info = runTool("ffprobe", "-v", "error", "-show_entries",
                        "stream=codec_name,sample_rate,channels", "-of", "default=noprint_wrappers=1", wav.toString());
                assertTrue(info.contains("pcm_s16le"), info);
                assertTrue(info.contains("sample_rate=16000"), info);
                assertTrue(info.contains("channels=1"), info);
            } finally {
                Files.deleteIfExists(wav);
            }
        } finally {
            Files.deleteIfExists(webm);
        }
    }

    @Test
    void transcodesMp4AacRecording() throws Exception {
        Path mp4 = fixture("recording.mp4", "sine=frequency=440:duration=3", List.of("-c:a", "aac"));
        try {
            var audio = normalize(props(), mockFile("recording.mp4", "audio/mp4", Files.readAllBytes(mp4)));
            assertEquals(3000L, audio.durationMs(), 250L);
        } finally {
            Files.deleteIfExists(mp4);
        }
    }

    @Test
    void rejectsUndecodableBytes() throws Exception {
        byte[] garbage = new byte[256];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) (i * 37 + 11);
        }
        var thrown = assertThrows(InterviewException.class,
                () -> normalize(props(), mockFile("recording.webm", "audio/webm", garbage)));
        assertEquals(415, thrown.status());
    }

    @Test
    void rejectsFileWithoutAudioTrack() throws Exception {
        Path video = fixture("silent-video.mp4", "color=c=black:s=64x64:d=2",
                List.of("-c:v", "libx264", "-preset", "ultrafast"));
        try {
            var thrown = assertThrows(InterviewException.class,
                    () -> normalize(props(), mockFile("answer.mp4", "video/mp4", Files.readAllBytes(video))));
            assertEquals(415, thrown.status());
        } finally {
            Files.deleteIfExists(video);
        }
    }

    @Test
    void rejectsOverlongRecording() throws Exception {
        // 121 秒样本约 3.69 MiB，在 5 MiB 上传上限之内——只比字节数拦不住它。
        Path webm = fixture("overlong.webm", "sine=frequency=440:duration=121",
                List.of("-c:a", "libopus", "-b:a", "64k"));
        try {
            var thrown = assertThrows(InterviewException.class,
                    () -> normalize(props(), mockFile("overlong.webm", "audio/webm", Files.readAllBytes(webm))));
            assertEquals(422, thrown.status());
        } finally {
            Files.deleteIfExists(webm);
        }
    }

    @Test
    void rejectsUploadOverTheSizeLimit() {
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
        var thrown = assertThrows(InterviewException.class,
                () -> normalize(props(), mockFile("huge.webm", "audio/webm", tooLarge)));
        assertEquals(413, thrown.status());
    }

    @Test
    void rejectsEmptyUpload() {
        assertEquals(400, assertThrows(InterviewException.class,
                () -> normalize(props(), mockFile("empty.webm", "audio/webm", new byte[0]))).status());
    }

    /**
     * 工具挂住时必须超时（504），不能一直等。
     *
     * <p><b>仅限 POSIX 平台</b>：这个用例用 {@code script("sleep 30")} 造一个假工具，
     * 而 {@code sleep}、{@code printf}、{@code $@}、{@code command} 都是 POSIX shell 的写法。
     * Windows 上脚本根本无法执行，"挂住"变成了"立刻失败"，
     * 断言拿到的就不是 504 而是 415——测试失败的原因和被测行为无关。
     * 与其在 Windows 上给一个假结论，不如明确跳过。
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void timesOutWhenTheToolHangs() throws Exception {
        Path webm = fixture("recording.webm", "sine=frequency=440:duration=2", List.of("-c:a", "libopus"));
        Path hanger = script("sleep 30\n");
        try {
            var settings = props();
            settings.setFfmpegPath(hanger.toString());
            settings.setConversionTimeoutSeconds(1);
            var thrown = assertThrows(InterviewException.class,
                    () -> normalize(settings, mockFile("recording.webm", "audio/webm", Files.readAllBytes(webm))));
            assertEquals(504, thrown.status());
        } finally {
            Files.deleteIfExists(webm);
            Files.deleteIfExists(hanger);
        }
    }

    /**
     * 转码进程退出码为 0 也不能信：产物必须是真 WAV，否则 ASR 一定会拒收。
     *
     * <p><b>仅限 POSIX 平台</b>：假工具是 shell 脚本。在 Windows 上它跑不起来，
     * 于是"脚本输出假 WAV"变成了"脚本执行失败"，异常照抛、断言照过——
     * <b>测试绿了，但什么都没验证</b>。这种假通过比失败更危险，所以直接跳过。
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsToolOutputThatIsNotReallyWav() throws Exception {
        // 转码进程退出码为 0 也不能信：产物必须是真 WAV，否则 ASR 一定会拒收。
        Path webm = fixture("recording.webm", "sine=frequency=440:duration=2", List.of("-c:a", "libopus"));
        Path liar = script("out=\"\"\nfor a in \"$@\"; do out=\"$a\"; done\nprintf 'not a wav' > \"$out\"\nexit 0\n");
        try {
            var settings = props();
            settings.setFfmpegPath(liar.toString());
            var thrown = assertThrows(InterviewException.class,
                    () -> normalize(settings, mockFile("recording.webm", "audio/webm", Files.readAllBytes(webm))));
            assertEquals(415, thrown.status());
        } finally {
            Files.deleteIfExists(webm);
            Files.deleteIfExists(liar);
        }
    }

    /**
     * 时长超限时必须报错，不能用 -t 默默截断：用户的 3 分钟回答不能被砍成 2 分钟再送识别。
     *
     * <p><b>仅限 POSIX 平台</b>：这里用一个 shell 包装脚本记录 ffmpeg 的实参，
     * 需要 POSIX shell 才能运行。Windows 上会直接报错，测不出"有没有偷偷加 -t"。
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void neverPassesATruncationFlag() throws Exception {
        // 时长超限时必须报错，不能用 -t 默默截断：用户的 3 分钟回答不能被砍成 2 分钟再送识别。
        Path webm = fixture("recording.webm", "sine=frequency=440:duration=5", List.of("-c:a", "libopus"));
        Path argsLog = Files.createTempFile("audio-normalizer-args-", ".txt");
        Files.write(argsLog, new byte[0]);
        Path wrapper = script("printf '%s\\n' \"$@\" >> " + argsLog + "\ncommand ffmpeg \"$@\"\n");
        try {
            var settings = props();
            settings.setFfmpegPath(wrapper.toString());
            var audio = normalize(settings, mockFile("recording.webm", "audio/webm", Files.readAllBytes(webm)));

            assertEquals(5000L, audio.durationMs(), 250L);
            List<String> commandLine = Files.readAllLines(argsLog, StandardCharsets.UTF_8);
            int argIndex = 0;
            while (argIndex < commandLine.size() && !commandLine.get(argIndex).equals("-i")) {
                argIndex++;
            }
            List<String> arguments = commandLine.subList(argIndex + 1, commandLine.size());
            assertFalse(arguments.contains("-t"), "转码命令里出现了 -t：" + arguments);
            assertFalse(arguments.contains("-ss"), "转码命令里出现了 -ss：" + arguments);
            assertTrue(arguments.contains("16000"), "仍是 16 kHz：" + arguments);
            assertTrue(arguments.contains("pcm_s16le"), "仍是 PCM16：" + arguments);
        } finally {
            Files.deleteIfExists(webm);
            Files.deleteIfExists(argsLog);
            Files.deleteIfExists(wrapper);
        }
    }

    @Test
    void cleansTemporaryFilesAfterFailure() throws Exception {
        byte[] garbage = new byte[256];
        var before = leftoverTempDirs();
        try {
            assertThrows(InterviewException.class, () -> normalize(props(),
                    mockFile("recording.webm", "audio/webm", garbage)));
        } finally {
            assertEquals(before, leftoverTempDirs(), "失败路径必须清干净临时目录");
        }
    }

    @Test
    void cleansTemporaryFilesAfterSuccess() throws Exception {
        Path webm = fixture("recording.webm", "sine=frequency=440:duration=2", List.of("-c:a", "libopus"));
        var before = leftoverTempDirs();
        try {
            normalize(props(), mockFile("recording.webm", "audio/webm", Files.readAllBytes(webm)));
            assertEquals(before, leftoverTempDirs(), "成功路径也必须清干净临时目录");
        } finally {
            Files.deleteIfExists(webm);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static AudioNormalizer.NormalizedAudio normalize(SpeechProperties settings, MockMultipartFile file) {
        return new AudioNormalizer(settings).normalize(file);
    }

    private static SpeechProperties props() {
        return new SpeechProperties();
    }

    private static MockMultipartFile mockFile(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    /** 返回 stdout；非 0 退出码或不按时退出都视为测试失败，不静默跳过。 */
    private static String runTool(String tool, String... args) {
        List<String> command = new ArrayList<>();
        command.add(tool);
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail(tool + " 未按时退出");
            }
            assertEquals(0, process.exitValue(), tool + " 执行失败：\n" + stdout);
            return stdout;
        } catch (IOException | InterruptedException e) {
            fail(tool + " 不可执行：" + e.getMessage());
            return "";
        }
    }

    /** 用 lavfi 生成测试音频，与真实浏览器录音走同一条 ffmpeg 路径。 */
    private static Path fixture(String fileName, String inputSpec, List<String> codecArgs) {
        Path file = null;
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", inputSpec));
        command.addAll(codecArgs);
        try {
            file = Files.createTempFile("audio-normalizer-fixture-", "-" + fileName);
            command.add(file.toString());
            Process process = new ProcessBuilder(command).start();
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                fail("生成测试音频失败：" + command);
            }
            return file;
        } catch (IOException | InterruptedException e) {
            fail("生成测试音频失败：" + e.getMessage());
            return file;
        }
    }

    private static Path script(String body) throws IOException {
        Path file = Files.createTempFile("audio-normalizer-tool-", ".sh");
        Files.write(file, (body + "\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(file.toFile().setExecutable(true), "无法把脚本设为可执行：" + file);
        return file;
    }

    private static Set<String> leftoverTempDirs() throws IOException {
        return Files.list(Paths.get(System.getProperty("java.io.tmpdir")))
                .map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith(TMP_PREFIX))
                .collect(java.util.stream.Collectors.toSet());
    }
}
