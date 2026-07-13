#!/usr/bin/env bash
#
# s22 P5 D4 —— jooj s22 迁移脚本:上线前一次性清空存量,让 transcript 从零构建。
#
# 参考:learnAi/dou/AI Agent 实战/Week10_Skills_MCP_协议/s22_改造规划_事件驱动Transcript.md §5.1
#
# ============================================================================
#  为什么需要清空
# ============================================================================
#
# s22 前:sessions/<sid>.json 里的 user 消息带 <memories>...</memories> 前缀污染
# s22 后:UI/搜索都从 transcript 派生,transcript 只从事件流构建(不消费历史 JSON)
#
# 存量 sessions:
#   - 里面的污染无法自动 backfill 进 transcript(memory 上下文已丢)
#   - 前端切到 transcript 后,老 session 显示为空(用户体验混乱)
#
# 一刀切清空是最省心的选择(D4)。
#
# ============================================================================
#  用法
# ============================================================================
#
#   scripts/s22-migrate.sh              # 默认清 ~/.jooj/
#   JOOJ_HOME=/custom/path scripts/s22-migrate.sh  # 清指定 JOOJ_HOME
#   scripts/s22-migrate.sh --dry-run    # 只打印要删的路径,不真删
#   scripts/s22-migrate.sh --force      # 跳过交互确认(用于 CI / 部署脚本 pipeline)
#
# ============================================================================
#  前置条件
# ============================================================================
#
# 1. jooj 进程必须**停止运行**(pidfile guard 会阻断,但脚本自己也再检查一次)
# 2. 备份存量对话数据(如需)—— 脚本执行后不可恢复
#
# ============================================================================

set -euo pipefail

# ── 参数解析 ──────────────────────────────────────────────────
DRY_RUN=0
FORCE=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --force)   FORCE=1 ;;
    -h|--help)
      sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "unknown arg: $arg" >&2
      echo "usage: $0 [--dry-run] [--force]" >&2
      exit 2
      ;;
  esac
done

# ── 定位 JOOJ_HOME ────────────────────────────────────────────
JOOJ_HOME="${JOOJ_HOME:-$HOME/.jooj}"
if [[ ! -d "$JOOJ_HOME" ]]; then
  echo "[s22-migrate] $JOOJ_HOME 不存在,无需清理"
  exit 0
fi

# ── 前置:确认没有 jooj 进程在跑 ──────────────────────────────
PID_FILE="$JOOJ_HOME/.pid"
if [[ -f "$PID_FILE" ]]; then
  EXISTING_PID=$(cat "$PID_FILE" 2>/dev/null | tr -d '[:space:]' || true)
  if [[ -n "$EXISTING_PID" ]] && kill -0 "$EXISTING_PID" 2>/dev/null; then
    echo "[s22-migrate] ERROR: jooj 进程仍在运行 (pid=$EXISTING_PID)" >&2
    echo "  请先停止 jooj:kill $EXISTING_PID (或 kill -9 强制)" >&2
    exit 1
  fi
fi

# ── 列要删的目标 ──────────────────────────────────────────────
TARGETS=(
  "$JOOJ_HOME/sessions"
  "$JOOJ_HOME/transcripts"
  "$JOOJ_HOME/search.db"
  "$JOOJ_HOME/search.db-wal"
  "$JOOJ_HOME/search.db-shm"
  "$PID_FILE"
)

echo "[s22-migrate] JOOJ_HOME=$JOOJ_HOME"
echo "[s22-migrate] 将清理:"
for t in "${TARGETS[@]}"; do
  if [[ -e "$t" ]]; then
    size=$(du -sh "$t" 2>/dev/null | awk '{print $1}')
    echo "  - $t  ($size)"
  fi
done

# ── 交互确认(非 --force / 非 --dry-run 时)─────────────────────
if [[ $DRY_RUN -eq 1 ]]; then
  echo "[s22-migrate] --dry-run 模式,不实际删除"
  exit 0
fi

if [[ $FORCE -eq 0 ]]; then
  echo ""
  read -r -p "确认清空这些数据?(不可恢复) [y/N] " ans
  if [[ ! "$ans" =~ ^[Yy]$ ]]; then
    echo "[s22-migrate] 已取消"
    exit 0
  fi
fi

# ── 执行删除 ──────────────────────────────────────────────────
for t in "${TARGETS[@]}"; do
  if [[ -e "$t" ]]; then
    rm -rf "$t"
    echo "[s22-migrate] removed: $t"
  fi
done

echo "[s22-migrate] ✓ 完成。下次启动 jooj 会重新初始化目录结构。"
