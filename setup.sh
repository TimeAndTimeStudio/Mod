#!/usr/bin/env bash
#
# Time SMP - server setup script
#
# Creates a ready-to-run Fabric server folder with:
#   - Official Fabric loader/server jar
#   - Fabric API
#   - Time SMP (timesmp) mod (latest build)
#   - World generation mods for the main world (lithostitched + tectonic)
#   - Additional mods (sit + otterlib)
#   - Performance/utility mods bundle (modo.zip: ferritecore, lithium,
#     krypton, c2me, modernfix, ...)
#   - The lobby world (world-lobby-2026-11-17.zip), installed into
#     worlds/lobby with all player data wiped
#   - server.properties tuned for Time SMP:
#       motd=Time SMP, max-players=5, survival locked,
#       online-mode=off, secure profile off, whitelist off (open server),
#       spawn-protection=0 (no common/spawn area - claimable everywhere),
#       difficulty=hard, view-distance=10, simulation-distance=6
#   - run.sh launcher where you choose how much RAM the server uses
#
# Missing system tools (curl, jq, unzip, sha1sum, Java 26+) are installed
# automatically with dnf (sudo is used when not running as root).
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

die() { echo "[setup] ERROR: $*" >&2; exit 1; }
log() { echo "[setup] $*"; }

# ---------------------------------------------------------------------------
# 0) System dependencies: curl, jq, unzip, sha1sum, Java 26+
#    Installed automatically with dnf.
# ---------------------------------------------------------------------------
java_major() {
  # Print the major version of the java on PATH (empty when java is missing).
  local line num
  line=$(java -version 2>&1 | head -1) || return 0
  num=$(printf '%s\n' "$line" | sed -E 's/.*"([^"]+)".*/\1/')
  case "$num" in
    1.*) printf '%s' "$num" | cut -d. -f2 ;;  # legacy 1.8 style
    *)   printf '%s' "$num" | grep -oE '^[0-9]+' || true ;;  # leading digits (e.g. 27-ea -> 27)
  esac
}

need_pkgs=()
command -v curl >/dev/null 2>&1 || need_pkgs+=(curl)
command -v jq >/dev/null 2>&1 || need_pkgs+=(jq)
command -v unzip >/dev/null 2>&1 || need_pkgs+=(unzip)
command -v sha1sum >/dev/null 2>&1 || need_pkgs+=(coreutils)

# Minecraft 26.3 needs Java 26 or newer.
JAVA_MAJOR=$(java_major)
if [ "${JAVA_MAJOR:-0}" -lt 26 ]; then
  need_pkgs+=(java26)
fi

if [ ${#need_pkgs[@]} -eq 0 ]; then
  log "system dependencies: OK (Java $JAVA_MAJOR)"
else
  log "installing missing system dependencies with dnf: ${need_pkgs[*]}"
  command -v dnf >/dev/null 2>&1 || die "dnf is required to install system dependencies (dnf-only system)"
  SUDO=""
  if [ "$(id -u)" -ne 0 ]; then
    command -v sudo >/dev/null 2>&1 || die "need root (or sudo) to install: ${need_pkgs[*]}"
    SUDO="sudo"
  fi
  PKGS=()
  for p in "${need_pkgs[@]}"; do
    case "$p" in
      java26) PKGS+=(java-26-openjdk-headless) ;;
      *) PKGS+=("$p") ;;
    esac
  done
  $SUDO dnf install -y "${PKGS[@]}"
fi

# Final check that everything is available now.
for cmd in curl jq java sha1sum unzip; do
  command -v "$cmd" >/dev/null 2>&1 || die "missing dependency: $cmd (install it and re-run)"
done
JAVA_MAJOR=$(java_major)
if [ "${JAVA_MAJOR:-0}" -lt 26 ]; then
  die "java ${JAVA_MAJOR:-?} found but Java 26+ is required (point PATH/JAVA_HOME at a Java 26 install and re-run)"
fi

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
# 3b) Resource pack (published with every release; served over HTTPS)
# ---------------------------------------------------------------------------
RESOURCE_PACK_URL="${RESOURCE_PACK_URL:-https://github.com/${REPO}/releases/latest/download/resourcepack.zip}"
log "downloading resource pack"
curl -fL -o resourcepack.zip "$RESOURCE_PACK_URL"
RESOURCE_PACK_SHA1=$(sha1sum resourcepack.zip | awk '{print $1}')

# ---------------------------------------------------------------------------
# 3c) World generation mods (main world only; published for 26.3)
# ---------------------------------------------------------------------------
WORLDGEN_RELEASE="${WORLDGEN_RELEASE:-mod_26.3}"
if [ "$MC_VERSION" = "26.3" ]; then
  log "downloading world generation mods (lithostitched + tectonic)"
  curl -fL -o mods/lithostitched-2.0.6-fabric-26.3.jar \
    "https://github.com/${REPO}/releases/download/${WORLDGEN_RELEASE}/lithostitched-2.0.6-fabric-26.3.jar"
  curl -fL -o mods/tectonic-3.0.32-fabric-26.3.jar \
    "https://github.com/${REPO}/releases/download/${WORLDGEN_RELEASE}/tectonic-3.0.32-fabric-26.3.jar"
else
  log "skipping world generation mods (only published for 26.3)"
fi

# ---------------------------------------------------------------------------
# 3d) Additional mods (sit + otterlib; published for 26.3)
# ---------------------------------------------------------------------------
if [ "$MC_VERSION" = "26.3" ]; then
  log "downloading additional mods (sit + otterlib)"
  curl -fL -o mods/sit.-1.2.6.4+26.3.jar \
    "https://github.com/${REPO}/releases/download/${WORLDGEN_RELEASE}/sit.-1.2.6.4+26.3.jar"
  curl -fL -o mods/otterlib-0.4.0.2+26.3-fabric.jar \
    "https://github.com/${REPO}/releases/download/${WORLDGEN_RELEASE}/otterlib-0.4.0.2+26.3-fabric.jar"
else
  log "skipping additional mods (only published for 26.3)"
fi

# ---------------------------------------------------------------------------
# 3f) Performance/utility mods bundle (modo.zip; published for 26.3)
# ---------------------------------------------------------------------------
if [ "$MC_VERSION" = "26.3" ]; then
  log "downloading performance mods bundle (modo.zip)"
  curl -fL -o /tmp/timesmp-modo.zip \
    "https://github.com/${REPO}/releases/download/${WORLDGEN_RELEASE}/modo.zip"
  unzip -oq /tmp/timesmp-modo.zip -d mods
  rm -f /tmp/timesmp-modo.zip
  log "performance mods bundle installed into mods/"
else
  log "skipping performance mods bundle (only published for 26.3)"
fi

# ---------------------------------------------------------------------------
# 3e) Lobby world (published on the maplobby release)
# ---------------------------------------------------------------------------
LOBBY_URL="${LOBBY_URL:-https://github.com/${REPO}/releases/download/maplobby/world-lobby-2026-11-17.zip}"
log "downloading lobby world"
curl -fL -o /tmp/timesmp-lobby.zip "$LOBBY_URL"
rm -rf worlds/lobby
mkdir -p worlds
unzip -q /tmp/timesmp-lobby.zip -d /tmp/timesmp-lobby-extract
mv /tmp/timesmp-lobby-extract/world worlds/lobby
# Wipe player data so the lobby starts clean
rm -rf worlds/lobby/players worlds/lobby/playerdata worlds/lobby/advancements worlds/lobby/stats
rm -f worlds/lobby/session.lock
rm -rf /tmp/timesmp-lobby.zip /tmp/timesmp-lobby-extract
log "lobby world installed at worlds/lobby (player data wiped)"

# ---------------------------------------------------------------------------
# 3g) Find world (published on the findmap release)
# ---------------------------------------------------------------------------
FIND_URL="${FIND_URL:-https://github.com/${REPO}/releases/download/findmap/find.zip}"
log "downloading find world"
curl -fL -o /tmp/timesmp-find.zip "$FIND_URL"
rm -rf worlds/find
mkdir -p worlds
rm -rf /tmp/timesmp-find-extract
unzip -q /tmp/timesmp-find.zip -d /tmp/timesmp-find-extract
mv /tmp/timesmp-find-extract/find worlds/find
# Wipe player data so the find world starts clean
rm -rf worlds/find/players worlds/find/playerdata worlds/find/advancements worlds/find/stats
rm -f worlds/find/session.lock
rm -rf /tmp/timesmp-find.zip /tmp/timesmp-find-extract
log "find world installed at worlds/find (player data wiped)"

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
white-list=false
enforce-whitelist=false
gamemode=survival
force-gamemode=true
hardcore=false
difficulty=hard
pvp=true
level-name=world
view-distance=10
simulation-distance=6
enable-status=true
spawn-protection=0
max-tick-time=180000
resource-pack=${RESOURCE_PACK_URL}
resource-pack-id=6d7ddf93-6004-4224-9d7a-2cbc7efd86a1
resource-pack-prompt={"text":"Time SMP Thai Font Fix & Custom UI"}
resource-pack-sha1=${RESOURCE_PACK_SHA1}
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
echo "The server is OPEN (no whitelist) with no common/spawn area:"
echo "  players can claim land at every point (spawn-protection=0, spawnProtectedCells=0)."
echo
echo "No admins are added automatically. Grant one in-game with:"
echo "  /op <player>"
