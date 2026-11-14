# Seasons — отчёт о переименовании

## Что переименовано (в репозитории)

| было | стало |
|---|---|
| каталог `seasonbiomes/` | `seasons/` |
| пакет `seasonbiomes` | `ru.geekideas.mc.seasons` |
| класс `SeasonBiomes` | `SeasonsPlugin` |
| `plugin.yml`: `name: SeasonBiomes` | `name: Seasons` |
| `plugin.yml`: `main: seasonbiomes.SeasonBiomes` | `main: ru.geekideas.mc.seasons.SeasonsPlugin` |
| команда `/biomeseason` + алиасы | только `/seasons`, `aliases:` в plugin.yml отсутствует |
| право `seasonbiomes.use` | `seasons.admin` |
| jar `seasonbiomes.jar` | `seasons-1.0.0.jar` |
| каталог данных | `plugins/Seasons` (по `name: Seasons`) |
| сообщения `[SeasonBiomes]` | `[Seasons]` |

`plugin.yml` целиком:

```yaml
name: Seasons
version: 1.0.0
main: ru.geekideas.mc.seasons.SeasonsPlugin
api-version: '26.3'
authors: [aristocr2t]
description: Карта исходных биомов в SQLite и сезонные проходы spring/summer/autumn/winter.
commands:
  seasons:
    usage: '/seasons <scan|spring|summer|autumn|winter|restore|status|stop> [радиус]'
    permission: seasons.admin
permissions:
  seasons.admin:
    default: op
```

## Что НЕ сделано и почему

Сборка, деплой и проверка запуска заблокированы: локальный shell упал с
`posix_spawnp failed` (ошибка не в командах — не запускается сам процесс оболочки,
тот же сбой был у дочерней сессии). Поэтому `seasons-1.0.0.jar` не собран,
в `server/plugins/` ничего не менялось, сервер не перезапускался.

Осталось выполнить (тот же порядок, что и раньше):

```sh
# 1. Освободить plugins/Seasons у SeasonsPlus (данные плагина не должны смешаться)
cd ~/repos/minecraft-server
docker exec minecraft_server sh -c 'mv /server/plugins/SeasonsPlus-1.4.2.jar /server/plugins/SeasonPlus-1.4.2.jar.disabled'
docker exec minecraft_server sh -c 'mv /server/plugins/Seasons /server/plugins/Seasons-old'

# 2. Собрать и положить новый jar, старый — в .disabled
cd seasons && sh build.sh            # соберёт seasons-1.0.0.jar
cd .. && cp seasons/seasons-1.0.0.jar server/plugins/
docker exec minecraft_server sh -c 'mv /server/plugins/seasonbiomes-1.0.0.jar /server/plugins/seasonbiomes-1.0.0.jar.disabled'

# 3. Рестарт (Chunky не запускать) и проверки
docker restart minecraft_server
grep -E "\[Seasons\]" server/logs/latest.log | head
./rcon "seasons status"
```

Ожидаемые строки в логе после успешного старта:

```
[Seasons] Loading server plugin Seasons v1.0.0
[Seasons] Enabling Seasons v1.0.0
[Seasons] маппинг: spring 0, summer 20, autumn 24, winter 22; высоты 40..320 ...
```

Важно: на сервере занят каталог `plugins/Seasons` (данные SeasonsPlus). Пока он не
переименован, плагин получит чужой каталог — сначала шаг 1, потом деплой.
SeasonsBar (`depend: [Seasons]`) собирается через API SeasonsPlus, поэтому после
отключения SeasonsPlus он не загрузится — это ожидаемо, решать отдельно.

## Что было проверено в предыдущей сборке (`SeasonBiomes`, та же логика)

```
[SeasonBiomes] Enabling SeasonBiomes v1.0.0
[SeasonBiomes] маппинг: spring 0, summer 20, autumn 24, winter 22; высоты 40..320
               (71 слоёв по 4 блока, 1136 клеток на чанк)
[scan] чанков на диске 23449, в карте уже 16299, к сканированию 7320, кольца от 0 до 522
[scan] завершено: 0 чанков, изменено 0 клеток, 22 c        (TPS 20.0)
```

`/biomeseason status` (старое имя команды):

```
карта: world — 16299 чанков, 18515664 клеток, 786 МБ
клетки по сезонам: spring 0, summer 0, autumn 0, winter 0, original 18515664; переписано 0
чанки по состоянию: original=16299
сканирование: карта пройдена до конца
последняя операция: scan: 0 чанков, изменено 0 клеток, 22 c
```

Схема карты (`plugins/Seasons/biomes.db`):

```sql
chunks(world, chunk_x, chunk_z, state, cells_total, cells_spring, cells_summer,
       cells_autumn, cells_winter, cells_original, cells_changed, scanned_at, applied_at,
       PRIMARY KEY (world, chunk_x, chunk_z))
cells(world, chunk_x, chunk_z, section_y, cell_x, cell_y, cell_z,
      original_biome,          -- INSERT OR IGNORE: не перезаписывается никогда
      applied_state,           -- original|spring|summer|autumn|winter
      PRIMARY KEY (world, chunk_x, chunk_z, section_y, cell_x, cell_y, cell_z)) WITHOUT ROWID
meta(k, v)                     -- scan_ring, scan_complete
```

## Замечание про `execute if biome`

Сверка карты с живым миром показала: в однородных пятнах значения совпадают, на
стыке биомов `execute if biome` иногда отдаёт биом соседней клетки (у команды есть
fuzz на границах). Карта хранит точные значения клеток — те, что читает
`World#getBiome`. Контрольные точки для проверок надо брать в середине однородных
пятен.

Инструменты проверки лежат в `seasons/tools/`: `biome.sh X Y Z biome`,
`where.sh X Y Z` (все совпавшие биомы), `diag3.sh` (сетка map/live).
