#!/usr/bin/env bash
#
# Time SMP - server setup script
#
# Creates a ready-to-run Fabric server folder with:
#   - Official Fabric loader/server jar
#   - Fabric API
#   - Time SMP (timesmp) mod
#   - server.properties tuned for Time SMP:
#       motd=Time SMP, max-players=5, survival locked,
#       online-mode=off, secure chat enforced, whitelist on,
#       view-distance=10, simulation-distance=6
#
# Usage:
#   ./setup.sh [server-dir]        # default: $HOME/mcserver
#   FORCE=1 ./setup.sh [dir]       # overwrite an existing install
#
set -euo pipefail

MC_VERSION="${MC_VERSION:-26.3}"
MOD_URL="${MOD_URL:-https://github.com/TimeAndTimeStudio/Mod/releases/download/Test/timesmp.jar}"
SERVER_DIR="${1:-$HOME/mcserver}"
ADMIN_NAME="TAT1654"
ADMIN_UUID="9db07e0d-2e66-3307-8695-509d6af83247"

die() { echo "[setup] ERROR: $*" >&2; exit 1; }
log() { echo "[setup] $*"; }

for cmd in curl jq java; do
  command -v "$cmd" >/dev/null 2>&1 || die "missing dependency: $cmd"
done

if [ -e "$SERVER_DIR/server.jar" ] && [ "${FORCE:-0}" != "1" ]; then
  die "$SERVER_DIR already contains server.jar (use FORCE=1 to overwrite)"
fi

log "server directory: $SERVER_DIR"
mkdir -p "$SERVER_DIR/mods"
cd "$SERVER_DIR"

# ---------------------------------------------------------------------------
# 1) Official Fabric server jar (meta.fabricmc.net)
# ---------------------------------------------------------------------------
log "resolving Fabric loader/installer for Minecraft $MC_VERSION"
LOADER=$(curl -fsSL https://meta.fabricmc.net/v2/versions/loader \
  | jq -r '[.[] | select(.stable)][0].version')
INSTALLER=$(curl -fsSL https://meta.fabricmc.net/v2/versions/installer \
  | jq -r '[.[] | select(.stable)][0].version')
[ -n "$LOADER" ] && [ "$LOADER" != "null" ] || die "could not resolve Fabric loader"
[ -n "$INSTALLER" ] && [ "$INSTALLER" != "null" ] || die "could not resolve Fabric installer"

log "downloading Fabric server (loader $LOADER / installer $INSTALLER)"
curl -fL -o server.jar \
  "https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${LOADER}/${INSTALLER}/server/jar"

# ---------------------------------------------------------------------------
# 2) Fabric API (Modrinth)
# ---------------------------------------------------------------------------
log "resolving Fabric API for Minecraft $MC_VERSION"
FABRIC_API_URL=$(curl -fsSL \
  "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22${MC_VERSION}%22%5D&loaders=%5B%22fabric%22%5D" \
  | jq -r '.[0].files[0].url')
[ -n "$FABRIC_API_URL" ] && [ "$FABRIC_API_URL" != "null" ] || die "could not resolve Fabric API"

log "downloading Fabric API"
curl -fL -o mods/fabric-api.jar "$FABRIC_API_URL"

# ---------------------------------------------------------------------------
# 3) Time SMP mod
# ---------------------------------------------------------------------------
log "downloading Time SMP mod"
curl -fL -o mods/timesmp.jar "$MOD_URL"

# ---------------------------------------------------------------------------
# 4) EULA
# ---------------------------------------------------------------------------
printf 'eula=true\n' > eula.txt

# ---------------------------------------------------------------------------
# 5) server.properties
# ---------------------------------------------------------------------------
log "writing server.properties"
cat > server.properties <<EOF
#Minecraft server properties - Time SMP
server-port=25565
server-ip=
motd=Time SMP
max-players=5
online-mode=false
enforce-secure-profile=true
white-list=true
enforce-whitelist=true
gamemode=survival
force-gamemode=true
hardcore=false
difficulty=easy
pvp=true
level-name=world
view-distance=10
simulation-distance=6
enable-status=true
spawn-protection=16
max-tick-time=180000
EOF

# ---------------------------------------------------------------------------
# 6) Admin: whitelist + op
# ---------------------------------------------------------------------------
log "whitelisting and opping $ADMIN_NAME"
cat > whitelist.json <<EOF
[
  {
    "uuid": "$ADMIN_UUID",
    "name": "$ADMIN_NAME"
  }
]
EOF

cat > ops.json <<EOF
[
  {
    "uuid": "$ADMIN_UUID",
    "name": "$ADMIN_NAME",
    "level": 4,
    "bypassesPlayerLimit": true
  }
]
EOF

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
log "done"
echo
echo "Install dir : $SERVER_DIR"
echo "Minecraft   : $MC_VERSION (Fabric loader $LOADER)"
echo "Java        : $(java -version 2>&1 | head -1)"
echo "Mods        : $(ls -1 mods | tr '\n' ' ')"
echo
echo "Start with:"
echo "  cd \"$SERVER_DIR\" && java -Xmx2G -jar server.jar nogui"
echo
echo "Whitelist is ON. Add players in-game with:"
echo "  /whitelist add <player>"
