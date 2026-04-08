#!/bin/bash
# ==============================================================================
# deploy-remote.sh — Clean, build, and deploy genqry backend to remote server
# ==============================================================================
# Remote: root@46.62.167.213 (SSH key auth, passphrase auto-loaded)
# ==============================================================================

set -e  # Exit on any error

# ── SSH Key Passphrase ────────────────────────────────────────────────────────
SSH_PASSPHRASE="hezkey"

# Load SSH key into agent so passphrase is not prompted during scp/ssh
eval "$(ssh-agent -s)" > /dev/null 2>&1
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
expect <<EOF
  spawn ssh-add
  expect "Enter passphrase"
  send "${SSH_PASSPHRASE}\r"
  expect eof
EOF

# Ensure ssh-agent is killed on exit
trap 'ssh-agent -k > /dev/null 2>&1' EXIT

# ── Configuration ─────────────────────────────────────────────────────────────
REMOTE_USER="root"
REMOTE_HOST="46.62.167.213"
REMOTE="${REMOTE_USER}@${REMOTE_HOST}"

JAR_NAME="genqry-0.0.1-SNAPSHOT.jar"
LOCAL_JAR="target/${JAR_NAME}"
REMOTE_APP_DIR="/opt/genqry"
REMOTE_JAR="${REMOTE_APP_DIR}/${JAR_NAME}"
SERVICE_NAME="genqry"
SPRING_PROFILE="secretsfree"

# ── Colors for output ────────────────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info()  { echo -e "${GREEN}[INFO]${NC}  $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# ── Step 1: Clean & Build ────────────────────────────────────────────────────
info "Step 1/5 — Cleaning and building the project..."
./mvnw clean package -DskipTests || error "Maven build failed!"
info "Build successful → ${LOCAL_JAR}"

# ── Step 2: Verify JAR exists ────────────────────────────────────────────────
if [ ! -f "${LOCAL_JAR}" ]; then
    error "JAR not found at ${LOCAL_JAR}"
fi
JAR_SIZE=$(du -h "${LOCAL_JAR}" | cut -f1)
info "Step 2/5 — JAR verified (${JAR_SIZE})"

# ── Step 3: Create remote directory (if needed) ─────────────────────────────
info "Step 3/5 — Preparing remote server..."
ssh "${REMOTE}" "mkdir -p ${REMOTE_APP_DIR}"

# ── Step 4: Upload JAR to remote server ──────────────────────────────────────
info "Step 4/5 — Uploading JAR to ${REMOTE}:${REMOTE_APP_DIR}/ ..."
scp "${LOCAL_JAR}" "${REMOTE}:${REMOTE_JAR}"
info "Upload complete."

# ── Step 5: Restart application on remote server ────────────────────────────
info "Step 5/5 — Restarting application on remote server..."
ssh "${REMOTE}" bash -s <<'REMOTE_SCRIPT'
    set -e

    # Source profile to get full PATH (non-interactive SSH doesn't load it)
    [ -f /etc/profile ] && . /etc/profile
    [ -f ~/.bashrc ]    && . ~/.bashrc
    export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/usr/lib/jvm/java-17-openjdk-amd64/bin:/usr/lib/jvm/java-17/bin:$PATH"

    APP_DIR="/opt/genqry"
    JAR="genqry-0.0.1-SNAPSHOT.jar"
    PROFILE="secretsfree"
    LOG_FILE="${APP_DIR}/genqry.log"

    # Find Java
    JAVA_BIN=$(which java 2>/dev/null || find /usr/lib/jvm /usr/local /opt -name "java" -type f 2>/dev/null | head -1)
    if [ -z "${JAVA_BIN}" ]; then
        echo "  ❌ Java not found on remote server! Install Java 17+:"
        echo "     apt update && apt install -y openjdk-17-jre-headless"
        exit 1
    fi
    echo "  → Using Java: ${JAVA_BIN}"
    ${JAVA_BIN} -version 2>&1 | head -1

    echo "  → Stopping existing genqry process (if running)..."
    pkill -f "${JAR}" 2>/dev/null && sleep 3 || echo "  → No existing process found."

    echo "  → Starting genqry with profile '${PROFILE}'..."
    cd "${APP_DIR}"
    nohup ${JAVA_BIN} -jar "${JAR}" \
        --spring.profiles.active="${PROFILE}" \
        > "${LOG_FILE}" 2>&1 &

    sleep 2
    if pgrep -f "${JAR}" > /dev/null; then
        echo "  ✅ genqry started successfully (PID: $(pgrep -f ${JAR}))"
        echo "  → Log: ${LOG_FILE}"
    else
        echo "  ❌ genqry failed to start. Check ${LOG_FILE}"
        tail -20 "${LOG_FILE}"
        exit 1
    fi
REMOTE_SCRIPT

echo ""
info "═══════════════════════════════════════════════════════════════"
info "  Deployment complete!"
info "  Server:  ${REMOTE_HOST}:9095"
info "  Profile: ${SPRING_PROFILE}"
info "  Log:     ssh ${REMOTE} 'tail -f ${REMOTE_APP_DIR}/genqry.log'"
info "═══════════════════════════════════════════════════════════════"

