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
#       online-mode=off, secure profile off, whitelist on,
#       difficulty=hard, view-distance=10, simulation-distance=6
#   - run.sh launcher where you choose how much RAM the server uses
#
# Usage:
#   ./setup.sh [server-dir]        # default: $HOME/mcserver
#   RAM=4G ./setup.sh [dir]        # skip the RAM prompt
#   FORCE=1 ./setup.sh [dir]       # overwrite an existing install
#   MOD_URL=... ./setup.sh [dir]   # use a specific mod jar instead of the latest release
#
set -euo pipefail

MC_VERSION="${MC_VERSION:-26.3}"
REPO="${REPO:-TimeAndTimeStudio/Mod}"
# The CI publishes to the release marked "latest": 26.3 is the historical
# timesmp.jar, other Minecraft versions use timesmp-mc<version>.jar.
if [ "$MC_VERSION" = "26.3" ]; then
  MOD_ASSET="timesmp.jar"
else
  MOD_ASSET="timesmp-mc${MC_VERSION}.jar"
fi
MOD_URL="${MOD_URL:-https://github.com/${REPO}/releases/latest/download/${MOD_ASSET}}"
SERVER_DIR="${1:-$HOME/mcserver}"
PLAYER_NAME="TAT1654"
PLAYER_UUID="9db07e0d-2e66-3307-8695-509d6af83247"

die() { echo "[setup] ERROR: $*" >&2; exit 1; }
log() { echo "[setup] $*"; }

for cmd in curl jq java; do
  command -v "$cmd" >/dev/null 2>&1 || die "missing dependency: $cmd"
done

# ---------------------------------------------------------------------------
# 0) RAM for run.sh (asked once, can be preset with RAM=4G ./setup.sh)
# ---------------------------------------------------------------------------
if [ -z "${RAM:-}" ]; then
  if [ -t 0 ]; then
    printf '[setup] RAM for the server in GB (e.g. 2, 4) [2]: '
    read -r RAM_INPUT || true
  fi
  RAM="${RAM_INPUT:-2}"
fi
if [[ "$RAM" =~ ^([0-9]+)$ ]]; then
  [ "$RAM" -ge 1 ] || die "invalid RAM '$RAM' (must be at least 1G)"
  RAM="${RAM}G"
elif [[ "$RAM" =~ ^([0-9]+)([GgMm])$ ]]; then
  RAM_NUM="${BASH_REMATCH[1]}"
  RAM_UNIT="${BASH_REMATCH[2]^^}"
  [ "$RAM_NUM" -ge 1 ] || die "invalid RAM '$RAM' (must be at least 1G)"
  RAM="${RAM_NUM}${RAM_UNIT}"
else
  die "invalid RAM '$RAM' (use a number of GB, e.g. 2, or a size like 4G)"
fi
log "server RAM: $RAM (change it in run.sh after setup)"

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
log "downloading Time SMP mod ($MOD_ASSET from latest release)"
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
enforce-secure-profile=false
white-list=true
enforce-whitelist=true
gamemode=survival
force-gamemode=true
hardcore=false
difficulty=hard
pvp=true
level-name=world
view-distance=10
simulation-distance=6
enable-status=true
spawn-protection=16
max-tick-time=180000
EOF

# ---------------------------------------------------------------------------
# 6) run.sh launcher (RAM is edited here; can also be overridden at start)
# ---------------------------------------------------------------------------
log "writing run.sh (RAM=$RAM)"
cat > run.sh <<'EOF'
#!/usr/bin/env bash
# Time SMP server launcher.
# Edit RAM below to choose how much memory the server may use, for example:
#   RAM="2G"   # small server
#   RAM="4G"   # busy server with several worlds loaded
# You can also override it when starting: RAM=8G ./run.sh
RAM="${RAM:-__DEFAULT_RAM__}"
cd "$(dirname "$0")"
exec java -Xms"$RAM" -Xmx"$RAM" -jar server.jar nogui
EOF
sed -i "s/__DEFAULT_RAM__/$RAM/" run.sh
chmod +x run.sh

# ---------------------------------------------------------------------------
# 7) Whitelist (no automatic admin/op)
# ---------------------------------------------------------------------------
log "whitelisting $PLAYER_NAME"
cat > whitelist.json <<EOF
[
  {
    "uuid": "$PLAYER_UUID",
    "name": "$PLAYER_NAME"
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
echo "  cd \"$SERVER_DIR\" && ./run.sh"
echo
echo "RAM is set in run.sh (currently $RAM). Edit it there or run:"
echo "  RAM=4G ./run.sh"
echo
echo "Whitelist is ON. Add players in-game with:"
echo "  /whitelist add <player>"
echo
echo "No admins are added automatically. Grant one in-game with:"
echo "  /op <player>"
