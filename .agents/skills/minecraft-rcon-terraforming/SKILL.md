---
name: minecraft-rcon-terraforming
description: Safely edit large Minecraft regions over RCON (fill/clone/place template), avoid gravity and water mistakes, force BlueMap re-render, and roll back when an edit goes wrong. Use when reshaping terrain, water, or a big build footprint on a live Paper 26.x server.
---

# Массовая правка мира через RCON

Опыт живой сессии на Paper 26.3 (спавн, вода, песок, BlueMap). Всё ниже — про правки объёмов
десятками тысяч блоков через `./rcon`, где ошибка видна не сразу.

## 0. Перед любой разрушительной правкой

1. `./snapshot <метка>` — tar мира в `/home/master/backups/mc` (save-all flush уже внутри).
2. Записать **точные** координаты объёма и высоты (пользователь ждёт цифры, не «примерно»).
3. Знать, что лежит под и над объёмом: вода? гравитационные блоки? чужая постройка?

Откат «назад к прошлому состоянию» без снапшота = перегенерация регионов + повторная
постановка постройки из `.nbt` (см. §6). Это работает, но теряет всё, что правили руками.

## 1. Лимит `fill`

`fill` больше **32 768** блоков **молча не выполняется** — ответ выглядит нормальным, мир не меняется.
Резать послойно, объём слоя считать заранее:

| область | блоков/слой |
|---|---|
| 151 × 139 | 20 989 |
| 125 × 125 | 15 625 |
| 117 × 117 | 13 689 |
| 113 × 113 | 12 769 |

Проверять, что команда сработала, ответом `Successfully filled N block(s)`, а не тишиной.

## 2. Гравитационные блоки (`sand`, `red_sand`, `gravel`, `*_concrete_powder`, `anvil`, `snow`)

- Чистка объёма — **только сверху вниз**. Иначе под песком появляется воздух и он осыпается вниз,
  обнажая стенки постройки.
- Насыпáть песок — **только снизу вверх с повторами**: слой за слоем, пока `fill ... replace water`
  не вернёт `0`. Один проход не заполняет: песок падает до опоры, а сверху снова вода.
  Практика: 1-й проход даёт десятки тысяч, дальше 100 → 30 → 0.
- `replace sand` на большом объёме **запрещён** — сносит природные песчаные слои (срезал гору).
- `brown_concrete_powder` в воде превращается в бетон — по воде класть обычный песок.

## 3. Вода

- Вода **подтекает по краям** объёма: остаток в 30–60 блоков на проход — это не баг скрипта,
  а приток снаружи. Либо герметизировать рамкой, либо тянуть заливку до берега.
- Засыпать воду песком «до поверхности» = уродливая корка и столбы на стыке с водой.
  Если нужен сухой участок — сначала решить, где будет стенка/берег.
- Пустоты (воздух) в воде заливать `fill ... water replace air` сверху вниз, пока не станет `0`
  (на практике: 54 358 → 8 → 0).
- Растения (`kelp`, `kelp_plant`, `seagrass`, `tall_seagrass`) **блокируют** `replace water`:
  сначала `fill ... air replace <растение>`, потом песок/вода.

## 4. BlueMap: перерисовка после правок

BlueMap реагирует **только на mtime регионов**, не на содержимое.

```bash
./rcon "save-all flush"; sleep 5
./rcon "bluemap stop"; sleep 2
rm -rf server/bluemap/web/maps/world/{tiles,rstate}
./rcon "bluemap reload"; sleep 8            # обязателен
find server/world/dimensions/minecraft/overworld/region -name "*.mca" -exec touch {} +
sleep 4; ./rcon "bluemap start"
```

- Без `save-all flush` BlueMap читает ещё не сброшенные на диск регионы.
- Проверка успеха — **размер папки карты** (`du -sm`), а не вывод команд: <15 МБ = не отрисовалось,
  иногда нужен второй заход.
- Регионы 26.x: `world/dimensions/minecraft/overworld/{region,entities,poi}` (не `world/region`).

## 5. Проверка фактами

Скриншот карты может отставать на минуты. Проверять блоками:

```bash
# есть ли конкретный блок
./rcon "execute if block 0 62 0 minecraft:grass_block run time query gametime"
# есть ли воздух
./rcon "execute if block 0 50 0 air run time query gametime"
# верхняя твёрдая поверхность по вертикали (бинарный поиск)
```

- `execute if block` на **незагруженных** чанках врёт → сначала `forceload add <chunk-координаты>`.
- `forceload add` принимает **координаты блоков** границ чанка: `add x1 z1 x2 z2`.
- rcon под нагрузкой (Chunky, BlueMap) рвёт соединение (`Error 111`) — повторять с паузами.

## 6. Откат: перегенерация участка + повторная постановка

Когда правки испортили рельеф, а снапшота нет:

```bash
./rcon "bluemap stop"; ./rcon "save-all flush"; sleep 5
docker stop minecraft_server
rm -f server/world/dimensions/minecraft/overworld/region/r.{0.0,-1.-1,-1.0,0.-1}.mca
docker start minecraft_server
# ждать rcon, затем forceload нужных чанков, чтобы чанки реально сгенерировались
```

Дальше — постановка постройки заново из шаблонов: `place template <id> <x> <y> <z>`
(шаблон `*.nbt` обязан быть gzip; `/place structure` не работает).
Чтобы сохранить рельеф под постройкой: склонировать его в буферную область
(`clone` по слоям ≤32 768), поставить шаблон, вернуть слои обратно — плита шаблона исчезает,
а грунт/вода остаются.

## 7. Снапшоты и откат отдельных участков

- `./snapshot <метка>` — весь мир в tar. Быстро, но целиком.
- WorldEdit snapshots: `plugins/WorldEdit/config.yml` → `snapshots: enabled: true`,
  затем `/snapshot list`, `/snapshot use <имя>`, `//restore`. Работает только для правок
  **после** включения и требует перезапуска сервера.
- Prism — блок-логирование с откатом по региону/игроку/времени (аналог CoreProtect),
  сборка под 26.3 существует. CoreProtect на 26.3 не работает.

## 8. Мелкие грабли 26.x

- Геймрулы snake_case: `natural_health_regeneration`, `respawn_radius`; `spawnRadius` (camelCase) тоже работает.
- `worldspawn` как команды не существует — `setworldspawn <x> <y> <z>`.
- `./rcon` внутри `while read` съедает stdin — писать `</dev/null` в каждой команде.
- macOS-файлы `._*` в плагинах ломают загрузку — `find … -name "._*" -delete`.
- `chunky progress` → `No tasks running` — это не ошибка, область уже сгенерирована.
