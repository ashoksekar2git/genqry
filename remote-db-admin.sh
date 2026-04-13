#!/bin/bash
# ==============================================================================
# remote-db-admin.sh — Connect to Hetzner managed PostgreSQL from remote server
# ==============================================================================
# This script SSHes into the remote backend server (46.62.167.213) and
# interacts with the Hetzner managed PostgreSQL (l0h9.your-database.de).
#
# Uses SSH ControlMaster multiplexing — you only enter the passphrase ONCE.
#
# Usage:
#   ./remote-db-admin.sh                    # Interactive menu
#   ./remote-db-admin.sh shell genqry       # Open psql shell to genqry DB
#   ./remote-db-admin.sh shell ecommerce    # Open psql shell to ecommerce DB
#   ./remote-db-admin.sh query genqry "SELECT * FROM users LIMIT 5;"
#   ./remote-db-admin.sh tables genqry      # List tables + row counts
#   ./remote-db-admin.sh tables ecommerce
#   ./remote-db-admin.sh health             # Check both DB connections
#   ./remote-db-admin.sh backup genqry      # Dump DB on remote server
#   ./remote-db-admin.sh backup ecommerce
#   ./remote-db-admin.sh exec genqry /path/to/file.sql  # Run SQL file
# ==============================================================================

set -euo pipefail

# ── SSH Configuration ─────────────────────────────────────────────────────────
SSH_PASSPHRASE="hezkey"
REMOTE_USER="root"
REMOTE_HOST="46.62.167.213"
REMOTE="${REMOTE_USER}@${REMOTE_HOST}"

# SSH ControlMaster — single connection, passphrase entered once
SSH_CONTROL_DIR="/tmp/ssh-genqry-admin"
SSH_CONTROL_PATH="${SSH_CONTROL_DIR}/%r@%h:%p"
SSH_OPTS="-o ControlPath=${SSH_CONTROL_PATH} -o ControlMaster=auto -o ControlPersist=300"

# ── Hetzner Managed PostgreSQL ────────────────────────────────────────────────
DB_HOST="l0h9.your-database.de"
DB_PORT="5432"

# Database: genqry
GENQRY_DB="genqry"
GENQRY_USER="ashoksekar"
GENQRY_PASSWORD="S33kingb@tss"

# Database: ecommerce
ECOMMERCE_DB="ecommerce"
ECOMMERCE_USER="mockdbroot"
ECOMMERCE_PASSWORD="S33kingb@tss"

# ── Colors ────────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# ── Helpers ───────────────────────────────────────────────────────────────────
info()  { echo -e "${GREEN}[INFO]  $1${NC}"; }
warn()  { echo -e "${YELLOW}[WARN]  $1${NC}"; }
error() { echo -e "${RED}[ERROR] $1${NC}"; }
step()  { echo -e "${CYAN}${BOLD}═══ $1 ═══${NC}"; }

# Wrappers that use the multiplexed connection
rssh()  { ssh ${SSH_OPTS} "${REMOTE}" "$@"; }
rscp()  { scp ${SSH_OPTS} "$@"; }

# ── SSH Multiplexing Setup ────────────────────────────────────────────────────
setup_ssh() {
    mkdir -p "${SSH_CONTROL_DIR}"

    # Check if master connection already exists
    if ssh ${SSH_OPTS} -O check "${REMOTE}" 2>/dev/null; then
        info "SSH multiplexed connection active"
        return 0
    fi

    # Open master connection (user enters passphrase once here)
    info "Establishing SSH connection to ${REMOTE}..."
    info "You will be prompted for the SSH key passphrase ONCE."
    ssh ${SSH_OPTS} -o ControlMaster=yes -o ControlPersist=300 -fN "${REMOTE}"

    if ssh ${SSH_OPTS} -O check "${REMOTE}" 2>/dev/null; then
        info "✅ SSH connection established (multiplexed)"
    else
        error "Failed to establish SSH connection"
        exit 1
    fi
}

# Cleanup master connection on exit
cleanup_ssh() {
    ssh ${SSH_OPTS} -O exit "${REMOTE}" 2>/dev/null || true
    rm -rf "${SSH_CONTROL_DIR}" 2>/dev/null || true
}
trap cleanup_ssh EXIT

# ── Check psql on remote ─────────────────────────────────────────────────────
check_psql() {
    if ! rssh "which psql" >/dev/null 2>&1; then
        warn "psql not found on remote server. Installing postgresql-client..."
        rssh "apt-get update -qq && apt-get install -y -qq postgresql-client" 2>&1 | tail -3
        if ! rssh "which psql" >/dev/null 2>&1; then
            error "Failed to install psql on remote server"
            exit 1
        fi
        info "✅ psql installed"
    fi
}

# ── Resolve DB credentials by name ───────────────────────────────────────────
resolve_db() {
    local db_name
    db_name=$(echo "${1}" | tr '[:upper:]' '[:lower:]')
    case "${db_name}" in
        genqry)
            echo "${GENQRY_DB}|${GENQRY_USER}|${GENQRY_PASSWORD}"
            ;;
        ecommerce)
            echo "${ECOMMERCE_DB}|${ECOMMERCE_USER}|${ECOMMERCE_PASSWORD}"
            ;;
        *)
            error "Unknown database: ${db_name}. Use 'genqry' or 'ecommerce'."
            exit 1
            ;;
    esac
}

# Helper: run psql command on remote via multiplexed SSH
run_psql() {
    local db="$1" user="$2" pass="$3"
    shift 3
    rssh "PGPASSWORD='${pass}' psql -h '${DB_HOST}' -p '${DB_PORT}' -U '${user}' -d '${db}' $*"
}

# ==============================================================================
# COMMAND: health — Check connectivity to both databases
# ==============================================================================
cmd_health() {
    step "Health Check — Hetzner PostgreSQL"

    for db_name in genqry ecommerce; do
        local creds
        creds=$(resolve_db "${db_name}")
        local db=$(echo "${creds}" | cut -d'|' -f1)
        local user=$(echo "${creds}" | cut -d'|' -f2)
        local pass=$(echo "${creds}" | cut -d'|' -f3)

        info "Testing ${db_name} (${DB_HOST}/${db} as ${user})..."
        local result
        result=$(run_psql "${db}" "${user}" "${pass}" "-t -c 'SELECT 1;'" 2>&1) || true

        if echo "${result}" | grep -q "1"; then
            info "✅ ${db_name} — connected"

            local tables
            tables=$(run_psql "${db}" "${user}" "${pass}" "-t -c \"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';\"" 2>/dev/null | tr -d ' ')
            info "   Tables: ${tables}"

            local size
            size=$(run_psql "${db}" "${user}" "${pass}" "-t -c \"SELECT pg_size_pretty(pg_database_size('${db}'));\"" 2>/dev/null | tr -d ' ')
            info "   Size: ${size}"
        else
            error "❌ ${db_name} — connection failed"
            echo "   ${result}" | head -3
        fi
    done
}

# ==============================================================================
# COMMAND: tables — List tables and row counts
# ==============================================================================
cmd_tables() {
    local db_name="${1:-}"
    [ -z "${db_name}" ] && { error "Usage: $0 tables <genqry|ecommerce>"; exit 1; }

    local creds
    creds=$(resolve_db "${db_name}")
    local db=$(echo "${creds}" | cut -d'|' -f1)
    local user=$(echo "${creds}" | cut -d'|' -f2)
    local pass=$(echo "${creds}" | cut -d'|' -f3)

    step "Tables in ${db_name} (${DB_HOST}/${db})"

    run_psql "${db}" "${user}" "${pass}" "-c \"
        SELECT
            t.tablename AS table_name,
            COALESCE(s.n_live_tup, 0) AS row_count,
            pg_size_pretty(pg_total_relation_size(quote_ident(t.tablename))) AS size
        FROM pg_tables t
        LEFT JOIN pg_stat_user_tables s ON s.relname = t.tablename
        WHERE t.schemaname = 'public'
        ORDER BY t.tablename;
    \"" 2>&1
}

# ==============================================================================
# COMMAND: query — Run a SQL query
# ==============================================================================
cmd_query() {
    local db_name="${1:-}"
    local sql="${2:-}"
    [ -z "${db_name}" ] || [ -z "${sql}" ] && {
        error "Usage: $0 query <genqry|ecommerce> \"SELECT ...\""
        exit 1
    }

    local creds
    creds=$(resolve_db "${db_name}")
    local db=$(echo "${creds}" | cut -d'|' -f1)
    local user=$(echo "${creds}" | cut -d'|' -f2)
    local pass=$(echo "${creds}" | cut -d'|' -f3)

    run_psql "${db}" "${user}" "${pass}" "-c \"${sql}\"" 2>&1
}

# ==============================================================================
# COMMAND: shell — Open interactive psql session
# ==============================================================================
cmd_shell() {
    local db_name="${1:-}"
    [ -z "${db_name}" ] && { error "Usage: $0 shell <genqry|ecommerce>"; exit 1; }

    local creds
    creds=$(resolve_db "${db_name}")
    local db=$(echo "${creds}" | cut -d'|' -f1)
    local user=$(echo "${creds}" | cut -d'|' -f2)
    local pass=$(echo "${creds}" | cut -d'|' -f3)

    step "Opening psql shell → ${DB_HOST}/${db} as ${user}"
    info "Type \\q to exit, \\dt to list tables, \\d <table> for schema"
    echo ""

    ssh ${SSH_OPTS} -t "${REMOTE}" "PGPASSWORD='${pass}' psql -h '${DB_HOST}' -p '${DB_PORT}' -U '${user}' -d '${db}'"
}

# ==============================================================================
# COMMAND: backup — Dump database on remote server
# ==============================================================================
cmd_backup() {
    local db_name="${1:-}"
    [ -z "${db_name}" ] && { error "Usage: $0 backup <genqry|ecommerce>"; exit 1; }

    local creds
    creds=$(resolve_db "${db_name}")
    local db=$(echo "${creds}" | cut -d'|' -f1)
    local user=$(echo "${creds}" | cut -d'|' -f2)
    local pass=$(echo "${creds}" | cut -d'|' -f3)

    local timestamp=$(date +"%Y%m%d_%H%M%S")
    local remote_dump="/tmp/${db}_backup_${timestamp}.sql"

    step "Backing up ${db_name} → ${remote_dump}"

    rssh "PGPASSWORD='${pass}' pg_dump \
        -h '${DB_HOST}' \
        -p '${DB_PORT}' \
        -U '${user}' \
        -d '${db}' \
        --no-owner \
        --no-privileges \
        -F p \
        -f '${remote_dump}' 2>&1 && \
        echo '✅ Dump saved:' && ls -lh '${remote_dump}'"

    echo ""
    info "Dump saved on remote server: ${remote_dump}"
    info "To download: scp ${REMOTE}:${remote_dump} ."
}

# ==============================================================================
# COMMAND: exec — Run a local SQL file against remote DB
# ==============================================================================
cmd_exec() {
    local db_name="${1:-}"
    local sql_file="${2:-}"
    [ -z "${db_name}" ] || [ -z "${sql_file}" ] && {
        error "Usage: $0 exec <genqry|ecommerce> /path/to/file.sql"
        exit 1
    }

    if [ ! -f "${sql_file}" ]; then
        error "SQL file not found: ${sql_file}"
        exit 1
    fi

    local creds
    creds=$(resolve_db "${db_name}")
    local db=$(echo "${creds}" | cut -d'|' -f1)
    local user=$(echo "${creds}" | cut -d'|' -f2)
    local pass=$(echo "${creds}" | cut -d'|' -f3)

    local remote_tmp="/tmp/exec_$(basename ${sql_file})_$(date +%s).sql"

    step "Executing ${sql_file} against ${db_name}"

    info "Uploading ${sql_file} → ${REMOTE}:${remote_tmp}"
    rscp "${sql_file}" "${REMOTE}:${remote_tmp}"

    info "Running SQL..."
    rssh "PGPASSWORD='${pass}' psql \
        -h '${DB_HOST}' \
        -p '${DB_PORT}' \
        -U '${user}' \
        -d '${db}' \
        -f '${remote_tmp}' 2>&1; \
        rm -f '${remote_tmp}'"

    info "✅ SQL execution complete"
}

# ==============================================================================
# COMMAND: schema — Show table schema (columns, types, constraints)
# ==============================================================================
cmd_schema() {
    local db_name="${1:-}"
    local table_name="${2:-}"
    [ -z "${db_name}" ] && { error "Usage: $0 schema <genqry|ecommerce> [table_name]"; exit 1; }

    local creds
    creds=$(resolve_db "${db_name}")
    local db=$(echo "${creds}" | cut -d'|' -f1)
    local user=$(echo "${creds}" | cut -d'|' -f2)
    local pass=$(echo "${creds}" | cut -d'|' -f3)

    if [ -z "${table_name}" ]; then
        step "Schema overview — ${db_name}"
        run_psql "${db}" "${user}" "${pass}" "-c \"
            SELECT
                t.tablename AS table_name,
                COUNT(c.column_name) AS columns,
                COALESCE(s.n_live_tup, 0) AS rows
            FROM pg_tables t
            JOIN information_schema.columns c
                ON c.table_name = t.tablename AND c.table_schema = 'public'
            LEFT JOIN pg_stat_user_tables s ON s.relname = t.tablename
            WHERE t.schemaname = 'public'
            GROUP BY t.tablename, s.n_live_tup
            ORDER BY t.tablename;
        \"" 2>&1
    else
        step "Schema — ${db_name}.${table_name}"
        run_psql "${db}" "${user}" "${pass}" "-c \"\\d+ ${table_name}\"" 2>&1
    fi
}

# ==============================================================================
# COMMAND: logs — Show recent backend application logs
# ==============================================================================
cmd_logs() {
    local lines="${1:-50}"
    step "Backend logs (last ${lines} lines)"
    rssh "tail -${lines} /opt/genqry/genqry.log 2>/dev/null || echo 'Log file not found'"
}

# ==============================================================================
# Interactive menu
# ==============================================================================
show_menu() {
    echo ""
    echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BOLD}║          genqry — Remote Database Admin Tool                ║${NC}"
    echo -e "${BOLD}║          Hetzner PostgreSQL: ${DB_HOST}           ║${NC}"
    echo -e "${BOLD}╠══════════════════════════════════════════════════════════════╣${NC}"
    echo -e "${BOLD}║  1) Health check          — Test both DB connections        ║${NC}"
    echo -e "${BOLD}║  2) List tables (genqry)  — Tables + row counts + sizes     ║${NC}"
    echo -e "${BOLD}║  3) List tables (ecommerce)                                 ║${NC}"
    echo -e "${BOLD}║  4) Schema overview (genqry)                                ║${NC}"
    echo -e "${BOLD}║  5) Schema overview (ecommerce)                             ║${NC}"
    echo -e "${BOLD}║  6) Open psql shell (genqry)                                ║${NC}"
    echo -e "${BOLD}║  7) Open psql shell (ecommerce)                             ║${NC}"
    echo -e "${BOLD}║  8) Run SQL query         — Enter custom query              ║${NC}"
    echo -e "${BOLD}║  9) Backup database       — pg_dump on remote               ║${NC}"
    echo -e "${BOLD}║ 10) View backend logs                                       ║${NC}"
    echo -e "${BOLD}║  0) Exit                                                    ║${NC}"
    echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

interactive_menu() {
    while true; do
        show_menu
        read -rp "Choose an option: " choice
        echo ""
        case "${choice}" in
            1)  cmd_health ;;
            2)  cmd_tables genqry ;;
            3)  cmd_tables ecommerce ;;
            4)  cmd_schema genqry ;;
            5)  cmd_schema ecommerce ;;
            6)  cmd_shell genqry ;;
            7)  cmd_shell ecommerce ;;
            8)
                read -rp "Database (genqry/ecommerce): " db
                read -rp "SQL query: " sql
                cmd_query "${db}" "${sql}"
                ;;
            9)
                read -rp "Database to backup (genqry/ecommerce): " db
                cmd_backup "${db}"
                ;;
            10) cmd_logs ;;
            0|q|exit) info "Bye!"; exit 0 ;;
            *)  warn "Invalid option: ${choice}" ;;
        esac
        echo ""
        read -rp "Press Enter to continue..."
    done
}

# ==============================================================================
# MAIN
# ==============================================================================

# Setup SSH multiplexed connection (passphrase entered once)
setup_ssh

# Verify psql is available on remote
check_psql

# Route command
CMD="${1:-menu}"
shift 2>/dev/null || true

case "${CMD}" in
    health)     cmd_health "$@" ;;
    tables)     cmd_tables "$@" ;;
    query)      cmd_query "$@" ;;
    shell)      cmd_shell "$@" ;;
    backup)     cmd_backup "$@" ;;
    exec)       cmd_exec "$@" ;;
    schema)     cmd_schema "$@" ;;
    logs)       cmd_logs "$@" ;;
    menu|"")    interactive_menu ;;
    *)
        echo "Usage: $0 <command> [args]"
        echo ""
        echo "Commands:"
        echo "  health                          Check both DB connections"
        echo "  tables <genqry|ecommerce>       List tables + row counts"
        echo "  schema <genqry|ecommerce> [tbl] Show table schema"
        echo "  query  <genqry|ecommerce> 'SQL' Run a SQL query"
        echo "  shell  <genqry|ecommerce>       Open interactive psql"
        echo "  backup <genqry|ecommerce>       Dump DB on remote server"
        echo "  exec   <genqry|ecommerce> file  Run SQL file against DB"
        echo "  logs   [lines]                  View backend app logs"
        echo "  menu                            Interactive menu (default)"
        exit 1
        ;;
esac

