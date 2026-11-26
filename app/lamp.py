"""
Управление Tuya-лампой по локальной сети (протокол 3.5) через tinytuya.

Секреты читаются из переменных окружения, в коде их нет:
    export TUYA_DEVICE_ID=...
    export TUYA_IP=192.168.0.100
    export TUYA_LOCAL_KEY=...        # 16 символов, из tinytuya wizard

Примеры:
    python lamp.py status
    python lamp.py on
    python lamp.py off
    python lamp.py color 255 0 0
    python lamp.py test              # красный, зелёный, синий, белый
"""

import os
import sys
import time

import tinytuya

PROTOCOL_VERSION = 3.5
TIMEOUT_S = 5
RETRIES = 3


def _env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        sys.exit(f"Не задана переменная окружения {name}")
    return value


def make_device() -> tinytuya.BulbDevice:
    """Создаёт подключение к лампе с явной версией протокола (автоопределение не используем)."""
    bulb = tinytuya.BulbDevice(
        dev_id=_env("TUYA_DEVICE_ID"),
        address=_env("TUYA_IP"),
        local_key=_env("TUYA_LOCAL_KEY"),
        version=PROTOCOL_VERSION,
    )
    bulb.set_socketTimeout(TIMEOUT_S)
    bulb.set_socketRetryLimit(RETRIES)
    return bulb


def call_with_retry(label: str, fn, *args):
    """Повтор с нарастающей паузой. Ошибку 914 (ключ или версия) выводим явно."""
    last = None
    for attempt in range(1, RETRIES + 1):
        result = fn(*args)
        if result and not result.get("Error"):
            return result
        last = result
        err = result.get("Error", "") if result else "пустой ответ"
        print(f"[{label}] попытка {attempt}/{RETRIES} не удалась: {err}", file=sys.stderr)
        if "914" in str(err):
            print("Ошибка 914: проверьте local key и версию протокола.", file=sys.stderr)
            break
        time.sleep(attempt)
    sys.exit(f"[{label}] не удалось: {last}")


def cmd_status(bulb: tinytuya.BulbDevice) -> None:
    data = call_with_retry("status", bulb.status)
    print(data)


def cmd_on(bulb: tinytuya.BulbDevice) -> None:
    call_with_retry("on", bulb.turn_on)
    print("включено")


def cmd_off(bulb: tinytuya.BulbDevice) -> None:
    call_with_retry("off", bulb.turn_off)
    print("выключено")


def cmd_color(bulb: tinytuya.BulbDevice, r: int, g: int, b: int) -> None:
    for name, value in (("r", r), ("g", g), ("b", b)):
        if not 0 <= value <= 255:
            sys.exit(f"Компонента {name} вне диапазона 0-255: {value}")
    call_with_retry("color", bulb.set_colour, r, g, b)
    print(f"цвет {r} {g} {b}")


def cmd_test(bulb: tinytuya.BulbDevice) -> None:
    for name, (r, g, b) in (
        ("красный", (255, 0, 0)),
        ("зелёный", (0, 255, 0)),
        ("синий", (0, 0, 255)),
        ("белый", (255, 255, 255)),
    ):
        call_with_retry("color", bulb.set_colour, r, g, b)
        print(name)
        time.sleep(1)
    print("тест пройден")


def main(argv: list[str]) -> None:
    if not argv:
        sys.exit(__doc__)
    bulb = make_device()
    command, args = argv[0], argv[1:]
    if command == "status":
        cmd_status(bulb)
    elif command == "on":
        cmd_on(bulb)
    elif command == "off":
        cmd_off(bulb)
    elif command == "color" and len(args) == 3:
        cmd_color(bulb, *(int(a) for a in args))
    elif command == "test":
        cmd_test(bulb)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
