#!/bin/bash
# Grindlands - запуск выделенного сервера openRPGator
# Требуется JDK 21 и собранный движок openRPGator
#
# Из корня репозитория openRPGator:
#   gradle :dedicated-server:installDist
#   ./grindlands_host/start.sh
#
# Или вручную:
#   ./dedicated-server/build/install/dedicated-server/bin/dedicated-server \
#       --data-dir grindlands_host --port 27800 --tick-rate 20

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Пытаемся найти dedicated-server
SERVER_BIN=""
for path in     "../dedicated-server/build/install/dedicated-server/bin/dedicated-server"     "../../openRPGator-master/dedicated-server/build/install/dedicated-server/bin/dedicated-server"     "../../dedicated-server/build/install/dedicated-server/bin/dedicated-server"; do
    if [ -f "$SCRIPT_DIR/$path" ]; then
        SERVER_BIN="$SCRIPT_DIR/$path"
        break
    fi
done

if [ -z "$SERVER_BIN" ] && command -v dedicated-server >/dev/null 2>&1; then
    SERVER_BIN="$(command -v dedicated-server)"
fi

if [ -z "$SERVER_BIN" ]; then
    echo "Ошибка: не найден dedicated-server. Соберите движок:"
    echo "  gradle :dedicated-server:installDist"
    exit 1
fi

echo "Запуск Grindlands на порту 27800..."
exec "$SERVER_BIN" --data-dir "$SCRIPT_DIR" --port 27800 --tick-rate 20
