#!/usr/bin/env python3
"""
拾阶求职工作台 · 火山语音链路冒烟测试（TTS → 文件 → ASR）

用途：
    在写后端适配器之前，用真实火山账户把两条链路各打通一次，留下可复现的证据：
    资源/音色权限是否可用、协议字段是否与 V3 文档一致、实际耗时、专有名词
    （Redis / Spring Boot）转写质量。

前置：
    1. 控制台已开通豆包语音，资源 id 为 volc.bigasr.auc_turbo（录音文件极速版
       识别）和 seed-tts-2.0（语音合成），且所选音色在该资源下有权限
    2. 本机可访问 openspeech.bytedance.com

用法：
    # 未配置 key：立即退出、不发任何请求、不产生费用
    python3 backend/tools/speech-smoke.py

    # 完整链路：合成一句面试题 → 落地 MP3 → 再识别这段音频
    VOLC_SPEECH_API_KEY='你的 key' python3 backend/tools/speech-smoke.py

    # 换音色（在控制台音色列表里选自己有权使用的）
    VOLC_SPEECH_API_KEY='你的 key' VOLC_TTS_SPEAKER='zh_female_xxx_bigtts' \
        python3 backend/tools/speech-smoke.py

    # 只识别本地录音（浏览器 MediaRecorder 产物，先转成 16k 单声道 wav）
    VOLC_SPEECH_API_KEY='你的 key' python3 backend/tools/speech-smoke.py \
        --asr-file out.wav

密钥只从环境变量读取：不写文件、不打印、不出现在任何输出里。
只依赖 Python 标准库；付费调用只在本脚本被显式运行时发生，CI 不跑本脚本。
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import pathlib
import tempfile
import time
import uuid
import urllib.error
import urllib.request

# 端点在冒烟脚本里固定写死，不接受参数或环境注入。
TTS_URL = 'https://openspeech.bytedance.com/api/v3/tts/unidirectional'
ASR_URL = 'https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash'
TTS_RESOURCE = 'seed-tts-2.0'
# 官方文档示例为 volc.bigasr.auc_turbo，但资源以账号实际授权为准。
# 实测：已开通流式识别资源的账号用 volc.seedasr.sauc.duration 调同一端点同样可用。
ASR_RESOURCE = os.environ.get('VOLC_ASR_RESOURCE_ID', 'volc.bigasr.auc_turbo').strip()
SUCCESS_CODE = '20000000'

# 固定样例：只用来验证专有名词能不能被识别出来，不是准确率基准。
SAMPLE_TEXT = '请介绍你如何用 Redis 优化 Spring Boot 项目的缓存。'
DEFAULT_SPEAKER = 'zh_female_vv_uranus_bigtts'
TTS_TIMEOUT = 30
ASR_TIMEOUT = 60

DEFAULT_MP3_NAME = 'interview-speech-smoke.mp3'


class SmokeError(RuntimeError):
    """链路失败，携带人可读原因。"""


def _upstream_detail(status, raw, log_id=None):
    """挑火山返回里人可读的字段；不整包打印上游 body。

    业务错误嵌在响应体的 header 对象里，例如
    {"header":{"code":45000030,"message":"[resource_id=...] not granted"}}，
    流式 TTS 的失败片段则是 code/message 在顶层。两处都覆盖。
    """
    bits = []
    if status:
        bits.append(f'X-Api-Status-Code={status}')
    try:
        node = json.loads(raw.decode('utf-8', 'replace'))
    except ValueError:
        node = None
    if isinstance(node, dict):
        for container in (node.get('header'), node):
            if not isinstance(container, dict):
                continue
            found = [f'{f}={container[f]}' for f in ('code', 'message')
                     if container.get(f) not in (None, '')]
            if found:
                bits.append('，'.join(found))
                break
    if log_id:
        bits.append(f'log_id={log_id}')
    if bits:
        return '，'.join(bits)
    return raw[:200].decode('utf-8', 'replace').replace('\n', ' ') \
        or '上游未返回可读错误信息'


def _post_json(url, key, resource_id, body, timeout, sequence=None):
    """发 JSON POST；HTTP 非 2xx 直接转成 SmokeError，由 main 统一收口。"""
    headers = {
        'Content-Type': 'application/json',
        'X-Api-Key': key,
        'X-Api-Resource-Id': resource_id,
        'X-Api-Request-Id': str(uuid.uuid4()),
    }
    if sequence is not None:
        headers['X-Api-Sequence'] = sequence
    request = urllib.request.Request(
        url, data=json.dumps(body).encode('utf-8'), headers=headers, method='POST')
    try:
        return urllib.request.urlopen(request, timeout=timeout)
    except urllib.error.HTTPError as error:
        detail = _upstream_detail(
            error.headers.get('X-Api-Status-Code'), error.read() or b'',
            error.headers.get('X-Tt-Logid'))
        raise SmokeError(f'{url} HTTP {error.code}：{detail}') from error
    except urllib.error.URLError as error:
        raise SmokeError(f'{url} 连接失败：{error.reason}') from error


def synthesize(key, speaker, text):
    """调 TTS 单向流式接口，逐个解码音频片段，拼出完整 MP3。"""
    body = {'req_params': {
        'text': text,
        'speaker': speaker,
        'audio_params': {'format': 'mp3', 'sample_rate': 24000},
    }}
    audio = bytearray()
    complete = False
    with _post_json(TTS_URL, key, TTS_RESOURCE, body, TTS_TIMEOUT) as response:
        for line in response:
            line = line.strip()
            if not line:
                continue
            try:
                part = json.loads(line.decode('utf-8', 'replace'))
            except ValueError as error:
                raise SmokeError(f'TTS 返回不是逐行 JSON：{error}') from error
            code = str(part.get('code', ''))
            if code == SUCCESS_CODE:
                complete = True
                break
            if code != '0':
                raise SmokeError('TTS 业务失败：' + _upstream_detail(
                    None, json.dumps(part, ensure_ascii=False).encode('utf-8')))
            data = part.get('data')
            if data:
                try:
                    audio.extend(base64.b64decode(data, validate=True))
                except ValueError as error:
                    raise SmokeError(f'TTS 音频片段 Base64 非法：{error}') from error
    if not complete:
        raise SmokeError('TTS 流没有成功结束事件（code=20000000），不返回半成品')
    if not audio:
        raise SmokeError('TTS 成功结束但没有任何音频数据')
    return bytes(audio)


def transcribe(key, audio, uid='resume-helper-smoke'):
    """调录音文件极速版识别；HTTP 状态与 X-Api-Status-Code 都必须是成功。"""
    body = {
        'user': {'uid': uid},
        'audio': {'data': base64.b64encode(audio).decode('ascii')},
        'request': {'model_name': 'bigmodel'},
    }
    with _post_json(ASR_URL, key, ASR_RESOURCE, body, ASR_TIMEOUT, sequence='-1') as response:
        status = response.headers.get('X-Api-Status-Code')
        raw = response.read()
    if status != SUCCESS_CODE:
        raise SmokeError('ASR 业务失败：' + _upstream_detail(status, raw))
    try:
        text = json.loads(raw.decode('utf-8', 'replace'))['result']['text']
    except (ValueError, KeyError, TypeError) as error:
        raise SmokeError(f'ASR 响应里没有 result.text：{error}') from error
    if not text.strip():
        raise SmokeError('ASR 成功但没有识别出文字（静音或过短）')
    return text


def _looks_like_mp3(data):
    """MP3 开头要么是 ID3 标签，要么是 MPEG 帧同步字。"""
    if data[:3] == b'ID3':
        return True
    return len(data) >= 2 and data[0] == 0xFF and (data[1] & 0xE0) == 0xE0


def main(argv=None):
    parser = argparse.ArgumentParser(
        description='火山语音 TTS → MP3 → ASR 冒烟测试（真实账户，会产生调用费用）')
    parser.add_argument('--asr-file', metavar='PATH',
                        help='只识别这个本地音频文件，不合成')
    parser.add_argument('--output', metavar='PATH',
                        help='合成的 MP3 落地路径，默认系统临时目录')
    args = parser.parse_args(argv)

    key = os.environ.get('VOLC_SPEECH_API_KEY', '').strip()
    if not key:
        print('请配置 VOLC_SPEECH_API_KEY')
        return 1
    speaker = os.environ.get('VOLC_TTS_SPEAKER', DEFAULT_SPEAKER).strip()

    try:
        if args.asr_file:
            path = pathlib.Path(args.asr_file)
            if not path.is_file():
                raise SmokeError(f'音频文件不存在：{args.asr_file}')
            audio = path.read_bytes()
            print(f'待识别音频：{path}（{len(audio):,} bytes）')
        else:
            started = time.perf_counter()
            audio = synthesize(key, speaker, SAMPLE_TEXT)
            if not _looks_like_mp3(audio):
                raise SmokeError('合成结果不是 MP3 帧格式，疑似音色或资源不匹配')
            output = pathlib.Path(args.output) if args.output else (
                pathlib.Path(tempfile.gettempdir()) / DEFAULT_MP3_NAME)
            output.write_bytes(audio)
            print(f'TTS 成功：{len(audio):,} bytes，耗时 '
                  f'{time.perf_counter() - started:.1f}s，资源 {TTS_RESOURCE}，音色 {speaker}')
            print(f'音频输出：{output}')

        started = time.perf_counter()
        text = transcribe(key, audio)
        print(f'ASR 成功：{len(text)} 字，耗时 {time.perf_counter() - started:.1f}s，资源 {ASR_RESOURCE}')
        print('识别文本：' + text)
    except SmokeError as error:
        print('冒烟失败：' + str(error))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
