#!/usr/bin/env bash
# Builds the demo mod and the bridge, and lays out a runnable Fabric server in server/run.
# Versions come from gradle.properties, so bumping Minecraft happens in one place.
# Needs Java 25, curl and python3.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
run="$root/server/run"
prop() { grep "^$1=" "$root/gradle.properties" | cut -d= -f2-; }

mc=$(prop minecraft_version)
loader=$(prop loader_version)
fabric_api=$(prop fabric_api_version)
polymer=$(prop polymer_version)
installer=1.1.2

mkdir -p "$run/mods" "$run/config/polymer"

launcher="$run/fabric-server-launcher.jar"
if [ ! -f "$launcher" ]; then
	echo "Downloading Fabric server launcher for $mc / loader $loader"
	curl -fsSL -o "$launcher" "https://meta.fabricmc.net/v2/versions/loader/$mc/$loader/$installer/server/jar"
fi

# Download one exact version of a Modrinth project into mods/, skipping it if present.
modrinth() {
	local project=$1 version=$2
	local encoded=${version//+/%2B}
	local info
	info=$(curl -fsSL "https://api.modrinth.com/v2/project/$project/version/$encoded")
	local name url
	name=$(python3 -c 'import json,sys; f=json.load(sys.stdin)["files"][0]; print(f["filename"])' <<<"$info")
	url=$(python3 -c 'import json,sys; f=json.load(sys.stdin)["files"][0]; print(f["url"])' <<<"$info")
	if [ ! -f "$run/mods/$name" ]; then
		echo "Downloading $name"
		curl -fsSL -o "$run/mods/$name" "$url"
	fi
}

modrinth fabric-api "$fabric_api"
modrinth polymer "$polymer"
# The mod the bridge is tested against. Delete it from mods/ to run without it.
modrinth farmers-delight-refabricated "$(prop farmers_delight_version)"

(cd "$root" && ./gradlew :demo:build :bridge:build -q)
rm -f "$run"/mods/j2b-demo-*.jar "$run"/mods/j2b-bridge-*.jar
cp "$root"/demo/build/libs/j2b-demo-*.jar "$root"/bridge/build/libs/j2b-bridge-*.jar "$run/mods/"

echo "eula=true" > "$run/eula.txt"
[ -f "$run/server.properties" ] || cp "$root/server/server.properties" "$run/server.properties"
[ -f "$run/config/polymer/auto-host.json" ] || cp "$root/server/auto-host.json" "$run/config/polymer/auto-host.json"

echo "Server ready. Start it with: cd server/run && java -Xmx2G -jar fabric-server-launcher.jar nogui"
