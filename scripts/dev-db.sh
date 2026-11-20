#!/usr/bin/env bash
# Manage a project-local PostgreSQL 16 cluster for development when Docker is
# unavailable. Data lives in .local/pgdata and is never committed.
#
#   ./scripts/dev-db.sh start|stop|status|reset
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/.local"
PG_HOME="$STATE/postgres"
PGBIN="$PG_HOME/bin"
PGDATA="$STATE/pgdata"
LOGFILE="$STATE/postgres.log"

PGPORT="${LOCAL_DB_PORT:-5433}"
PGUSER="${LOCAL_DB_USER:-smartprint}"
PGPASSWORD_VALUE="${LOCAL_DB_PASSWORD:-change_me_local}"
PGDATABASE="${LOCAL_DB_NAME:-smartprint_db}"
BINARIES_VERSION="${LOCAL_PG_VERSION:-16.2.0}"
BINARIES_URL="https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-linux-amd64/${BINARIES_VERSION}/embedded-postgres-binaries-linux-amd64-${BINARIES_VERSION}.jar"

log() { printf '%s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }

ensure_binaries() {
    if [ -x "$PGBIN/initdb" ]; then
        return
    fi
    if command -v initdb >/dev/null 2>&1; then
        PGBIN="$(dirname "$(command -v initdb)")"
        log "Using system PostgreSQL binaries from $PGBIN"
        return
    fi

    local jar=""
    jar="$(find "${HOME}/.m2/repository/io/zonky/test/postgres" \
        -name "embedded-postgres-binaries-linux-amd64-${BINARIES_VERSION}.jar" 2>/dev/null | head -1 || true)"

    if [ -z "$jar" ]; then
        mkdir -p "$STATE"
        jar="$STATE/embedded-postgres-binaries.jar"
        if [ ! -f "$jar" ]; then
            log "Downloading PostgreSQL ${BINARIES_VERSION} binaries from Maven Central..."
            curl -fsSL "$BINARIES_URL" -o "$jar" || fail "Could not download PostgreSQL binaries. Install PostgreSQL 16 or use Docker."
        fi
    fi

    log "Extracting PostgreSQL binaries into .local/postgres ..."
    mkdir -p "$PG_HOME"
    unzip -p "$jar" 'postgres-linux-x86_64.txz' > "$STATE/postgres-binaries.txz"
    tar -xJf "$STATE/postgres-binaries.txz" -C "$PG_HOME"
    rm -f "$STATE/postgres-binaries.txz"
    [ -x "$PGBIN/initdb" ] || fail "Extraction did not produce $PGBIN/initdb"
}

export_libs() {
    if [ -d "$PG_HOME/lib" ]; then
        export LD_LIBRARY_PATH="$PG_HOME/lib:${LD_LIBRARY_PATH:-}"
    fi
}

initialise() {
    [ -d "$PGDATA" ] && return
    export_libs
    mkdir -p "$STATE"
    local pwfile
    pwfile="$(mktemp)"
    printf '%s' "$PGPASSWORD_VALUE" > "$pwfile"
    log "Initialising PostgreSQL cluster in .local/pgdata ..."
    "$PGBIN/initdb" -D "$PGDATA" -U "$PGUSER" --pwfile="$pwfile" \
        --auth-host=scram-sha-256 --auth-local=trust \
        --encoding=UTF8 --locale=C >/dev/null
    rm -f "$pwfile"
}

# initdb only creates postgres/template databases. CREATE DATABASE cannot run in a
# transaction, so it is issued in single-user mode while the server is stopped.
create_database() {
    export_libs
    printf 'CREATE DATABASE "%s" OWNER "%s";\n' "$PGDATABASE" "$PGUSER" \
        | "$PGBIN/postgres" --single -D "$PGDATA" postgres >/dev/null 2>&1 || true
}

is_running() {
    [ -d "$PGDATA" ] && "$PGBIN/pg_ctl" -D "$PGDATA" status >/dev/null 2>&1
}

start() {
    ensure_binaries
    export_libs
    initialise
    if is_running; then
        log "PostgreSQL already running on port $PGPORT"
    else
        create_database
        "$PGBIN/pg_ctl" -D "$PGDATA" -l "$LOGFILE" -w \
            -o "-p $PGPORT -c listen_addresses=127.0.0.1 -c unix_socket_directories='$STATE'" \
            start >/dev/null
        log "PostgreSQL started on port $PGPORT (database: $PGDATABASE, log: .local/postgres.log)"
    fi
    log ""
    log "Run the backend with:"
    log "  DB_URL=jdbc:postgresql://127.0.0.1:$PGPORT/$PGDATABASE \\"
    log "  DB_USER=$PGUSER DB_PASSWORD=$PGPASSWORD_VALUE \\"
    log "  mvn -f backend/pom.xml spring-boot:run"
}

stop() {
    ensure_binaries
    export_libs
    if is_running; then
        "$PGBIN/pg_ctl" -D "$PGDATA" -m fast -w stop >/dev/null
        log "PostgreSQL stopped"
    else
        log "PostgreSQL is not running"
    fi
}

status() {
    ensure_binaries
    export_libs
    if is_running; then
        log "PostgreSQL running on port $PGPORT (database $PGDATABASE, user $PGUSER)"
    else
        log "PostgreSQL is not running"
        exit 1
    fi
}

reset() {
    stop || true
    rm -rf "$PGDATA"
    log "Removed .local/pgdata — run 'start' to recreate it"
}

case "${1:-start}" in
    start) start ;;
    stop) stop ;;
    status) status ;;
    reset) reset ;;
    *) fail "usage: $0 start|stop|status|reset" ;;
esac
