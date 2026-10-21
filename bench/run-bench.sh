# 在 Git Bash / Linux 里跑 Token 探针
#
# 用法：
#   ./run-bench.sh                # 跑默认 3 个 query（需要 SearXNG 在 localhost:8888）
#   ./run-bench.sh --selftest     # 只做分词器自证，不联网、不需要 Docker

set -e

BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJ_DIR="$(dirname "$BENCH_DIR")"
BOOT_DIR="$PROJ_DIR/milan-ai-robot-springboot"
CP_FILE="$BENCH_DIR/.classpath.txt"
MVN="/d/apache-maven-3.9.16-bin/apache-maven-3.9.16/bin/mvn"
JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-21.0.10}"

if [ ! -f "$CP_FILE" ]; then
    echo "[1/3] 导出依赖 classpath ..."
    (cd "$BOOT_DIR" && "$MVN" -o -q dependency:build-classpath "-Dmdep.outputFile=$CP_FILE")
fi

echo "[2/3] 编译 TokenBench ..."
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -cp "$(cat "$CP_FILE")" \
    -d "$BENCH_DIR/classes" "$BENCH_DIR/TokenBench.java"

echo "[3/3] 运行 ..."
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    -cp "$BENCH_DIR/classes;$(cat "$CP_FILE")" TokenBench "$@"
