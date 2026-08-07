#!/bin/sh
set -e
DIR="$(dirname "$0")/../app/src/main/assets"
mkdir -p "$DIR"
curl -sL -o "$DIR/geoip.db" "https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db"
curl -sL -o "$DIR/geosite.db" "https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db"
ls -lh "$DIR"
