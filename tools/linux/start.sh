#!/bin/sh
# =============================================================================
#  拾阶求职工作台 · Linux 启动脚本
#
#  和 Windows 版一样：包里自带 JRE，服务器上什么都不用装。
#
#  用法：
#    sh start.sh                      # 前台运行（Ctrl+C 停止，适合先试一下）
#    nohup sh start.sh > logs/out.log 2>&1 &     # 后台运行
#    systemd（推荐，见 resume-helper.service）   # 开机自启 + 挂了自动重启
#
#  环境变量：
#    PORT=8080       监听端口
#    JVM_MEM=768m    最大堆。2G 内存用 768m；1G 内存改成 384m
# =============================================================================
set -e

# 包根目录（脚本所在目录），以及 jar 所在的 app/ 目录
ROOT="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$ROOT/app"

PORT="${PORT:-8080}"
JVM_MEM="${JVM_MEM:-768m}"

# 自带 JRE 优先，没有就用系统的 java
#
# 这里要处理一个很常见的坑：从 Windows 上打的 tar 包**不保留 Unix 可执行位**，
# 解压后 jre/bin/java 存在但不可执行，报 "Permission denied"。
# 那个报错对不熟 Linux 的人很难看懂，所以这里自己补上。
JAVA=""
if [ -x "$ROOT/jre/bin/java" ]; then
    JAVA="$ROOT/jre/bin/java"
elif [ -f "$ROOT/jre/bin/java" ]; then
    chmod +x "$ROOT/jre/bin/java" 2>/dev/null || true
    [ -x "$ROOT/jre/bin/java" ] && JAVA="$ROOT/jre/bin/java"
fi
if [ -z "$JAVA" ] && command -v java >/dev/null 2>&1; then
    JAVA="java"
fi
if [ -z "$JAVA" ]; then
    echo "[ERROR] 找不到可用的 Java。"
    echo "        包里应当自带 jre/；或者服务器上装 Java 17。"
    echo "        如果 jre/ 在但报权限错误，执行： chmod +x jre/bin/*"
    exit 1
fi

# 找 jar（不写死版本号，将来升级包不用改脚本）
JAR="$(ls "$APP_DIR"/resume-helper-backend-*.jar 2>/dev/null | head -1)"
if [ -z "$JAR" ]; then
    echo "[ERROR] 在 app/ 下找不到 resume-helper-backend-*.jar"
    exit 1
fi

echo "============================================================"
echo "  拾阶求职工作台"
echo "============================================================"
echo "  Java : $JAVA"
echo "  Jar  : $(basename "$JAR")"
echo "  Port : $PORT"
echo "  堆   : $JVM_MEM"
echo ""
echo "  访问地址： http://<这台服务器的IP>:$PORT/"
echo "  停止服务： Ctrl+C（systemd 方式则 systemctl stop resume-helper）"
echo "============================================================"

mkdir -p "$ROOT/logs"

# ★ 必须切到 app/ 再启动 ★
#
# 前端静态文件的位置配的是 `file:./webapp/`（见 application.yml 的
# spring.web.resources.static-locations），是**相对工作目录**找的。
# 如果在包根目录启动，就会去找 `<包根>/webapp/`，找不到 →
# 结果就是**接口正常、但所有前端页面 404**，而且日志里一行错都没有，
# 排查起来非常绕。Windows 启动器里那句 `cd /d "%~dp0app"` 就是干这个的。
cd "$APP_DIR"

# -Dfile.encoding=UTF-8 是必须的：
#   简历、岗位、画像里全是中文，而且会把中文写进 JSON 文件。
#   服务器 locale 不是 UTF-8 时，不指定就会出现乱码。
# -Djava.awt.headless=true：
#   服务器没有图形界面，PDFBox / POI 用到 AWT 时不会去找 X11。
exec "$JAVA" \
    -Dfile.encoding=UTF-8 \
    -Dsun.jnu.encoding=UTF-8 \
    -Djava.awt.headless=true \
    -Xmx"$JVM_MEM" \
    -jar "$JAR" \
    --server.port="$PORT"
