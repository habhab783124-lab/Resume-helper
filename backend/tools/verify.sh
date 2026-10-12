#!/usr/bin/env bash
# 拾阶求职工作台 · 岗位模块离线自检（Linux / macOS / Git Bash）
#
# 用途：在没有 Maven、装不上依赖的机器上，直接用 JDK 自带的 javac
#       编译岗位模块全部代码并跑端到端自检。
#
# 原理：core 层不依赖 Spring；Spring / Jackson / Jakarta 的类型由
#       tools/verify-stubs 下的编译期打桩替代。打桩只在本次校验中使用，
#       不在 src/main/java 里，不会被 Maven 打包。
#
# 用法：在 backend 目录下执行  bash tools/verify.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND="$(dirname "$SCRIPT_DIR")"
OUT="$SCRIPT_DIR/.verify-out"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

rm -rf "$OUT"
mkdir -p "$OUT"

# 收集源文件（用 @argfile 避免参数过长）
LIST="$SCRIPT_DIR/sources.txt"
find "$BACKEND/src/main/java" "$SCRIPT_DIR/verify-stubs" "$BACKEND/src/test/java" \
    -name '*.java' -type f > "$LIST"

echo "编译 $(wc -l < "$LIST") 个文件 ..."
"$JAVAC" -encoding UTF-8 -d "$OUT" "@$LIST"
echo "编译通过"

cp -r "$BACKEND"/src/main/resources/* "$OUT"/ 2>/dev/null || true

echo
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT" com.shijie.resumehelper.job.JobAgentOfflineDemo
