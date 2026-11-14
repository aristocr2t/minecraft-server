# Seasons

Микроплагин Paper 26.3: строит **карту исходных биомов** в SQLite и по ней выполняет
сезонные проходы `winter` / `summer` / `restore`. Мир при этом не пересоздаётся,
биомы меняются только в клетках 4×4×4 (гранулярность самой игры).

## Команды

```
//seasons status                          состояние карты и последняя операция
//seasons scan start [радиусБлоков]       строить/достраивать карту от центра мира (0,0) наружу
//seasons scan stop                       остановить сканирование, прогресс сохраняется
//seasons scan status                     то же, что status
//seasons spring [радиусБлоков]           весна  (по умолчанию = возврат original_biome)
//seasons summer [радиусБлоков]           лето   (summer-map)
//seasons autumn [радиусБлоков]           осень  (autumn-map)
//seasons winter [радиусБлоков]           зима   (winter-map)
//seasons restore [радиусБлоков]          вернуть original_biome из карты
//seasons stop                            остановить текущий проход
```

Право: `seasons.admin` (по умолчанию у операторов). Алиасов у команды нет.
Радиус необязателен и только ограничивает работу (`0` или отсутствие = вся карта);
плагин идёт от центра мира кольцами чанков, поэтому радиус — это «докуда от центра».

## Маппинг сезонов (config.yml)

Ключ таблиц — `original_biome` из базы, значение — биом сезона. Билд по умолчанию:

| original_biome | spring | summer | autumn | winter |
|---|---|---|---|---|
| plains | *оригинал* | sunflower_plains | windswept_hills | snowy_plains |
| forest | *оригинал* | forest | dark_forest | snowy_taiga |
| taiga | *оригинал* | taiga | snowy_taiga | snowy_taiga |
| old_growth_pine_taiga | *оригинал* | *оригинал* | snowy_taiga | snowy_taiga |
| old_growth_spruce_taiga | *оригинал* | *оригинал* | snowy_taiga | snowy_taiga |
| swamp | *оригинал* | swamp | dark_forest | snowy_taiga |
| jungle | *оригинал* | jungle | dark_forest | snowy_taiga |
| savanna | *оригинал* | savanna | windswept_savanna | snowy_plains |
| desert | *оригинал* | desert | badlands | snowy_plains |
| beach | *оригинал* | beach | stony_shore | snowy_beach |
| ocean | *оригинал* | warm_ocean | cold_ocean | frozen_ocean |
| river | *оригинал* | river | river | frozen_river |
| cherry_grove | *оригинал* | forest | dark_forest | snowy_taiga |
| windswept_hills | *оригинал* | *оригинал* | snowy_slopes | *оригинал* |
| windswept_gravelly_hills | *оригинал* | *оригинал* | snowy_slopes | *оригинал* |
| windswept_forest | *оригинал* | *оригинал* | snowy_taiga | *оригинал* |
| meadow | *оригинал* | *оригинал* | snowy_slopes | *оригинал* |
| stony_peaks | *оригинал* | *оригинал* | snowy_slopes | *оригинал* |
| snowy_taiga | *оригинал* | taiga | taiga | snowy_taiga |
| snowy_plains | *оригинал* | plains | plains | snowy_plains |
| snowy_beach | *оригинал* | beach | beach | snowy_beach |
| snowy_slopes | *оригинал* | meadow | *оригинал* | snowy_slopes |
| frozen_ocean | *оригинал* | ocean | ocean | frozen_ocean |
| frozen_river | *оригинал* | river | river | frozen_river |
| frozen_peaks | *оригинал* | jagged_peaks | *оригинал* | frozen_peaks |
| ice_spikes | *оригинал* | snowy_plains | *оригинал* | ice_spikes |
| jagged_peaks | *оригинал* | jagged_peaks | jagged_peaks | frozen_peaks |

Биомы, которых нет в таблицах (badlands, mushroom_fields, deep_dark, пещеры,
stony_shore, cold_ocean, snowy_* вне списка и т.п.), не меняются никогда.

`spring` по умолчанию возвращает `original_biome` (как `restore`); в `spring-map`
можно задать отдельную весну, секция в конфиге уже есть и закомментирована.

## Схема базы `plugins/Seasons/biomes.db`

```sql
-- служебное: что просканировано и итоговые счётчики по чанку
CREATE TABLE chunks (
  world         TEXT    NOT NULL,   -- имя мира (только overworld)
  chunk_x       INTEGER NOT NULL,
  chunk_z       INTEGER NOT NULL,
  state         TEXT    NOT NULL,   -- original | winter | summer: последний применённый сезон
  cells_total   INTEGER NOT NULL,
  cells_original INTEGER NOT NULL,
  cells_winter  INTEGER NOT NULL,
  cells_summer  INTEGER NOT NULL,
  cells_changed INTEGER NOT NULL,   -- переписано последней операцией
  scanned_at    INTEGER NOT NULL,
  applied_at    INTEGER,
  PRIMARY KEY (world, chunk_x, chunk_z)
);

-- карта биомов: одна строка = одна клетка 4×4×4
CREATE TABLE cells (
  world          TEXT    NOT NULL,
  chunk_x        INTEGER NOT NULL,
  chunk_z        INTEGER NOT NULL,
  section_y      INTEGER NOT NULL,  -- y >> 4, секция 16×16×16
  cell_x         INTEGER NOT NULL,  -- 0..3: кварт-координата внутри чанка
  cell_y         INTEGER NOT NULL,  -- 0..3: кварт-слой внутри секции
  cell_z         INTEGER NOT NULL,
  original_biome TEXT    NOT NULL,  -- НЕИЗМЕНЯЕМО: пишется один раз при первом сканировании
  applied_state  TEXT    NOT NULL,  -- original | winter | summer
  PRIMARY KEY (world, chunk_x, chunk_z, section_y, cell_x, cell_y, cell_z)
) WITHOUT ROWID;

CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);  -- scan_ring, scan_complete
CREATE INDEX chunks_state ON chunks (world, state);
```

Почему в ключе есть `cell_x/cell_y/cell_z`: в секции 16×16×16 помещаются 64 клетки
биомов, и на границе биомов в одной секции лежат разные биомы. Ключ владельца
`world + chunkX + chunkZ + sectionY` сохранён полностью, но уточнён до клетки —
иначе `restore` не смог бы вернуть границы биомов.

Адрес клетки восстанавливается однозначно: `blockX = chunk_x*16 + cell_x*4`,
`blockY = section_y*16 + cell_y*4`, `blockZ = chunk_z*16 + cell_z*4`.

Запись `original_biome` — `INSERT OR IGNORE`: повторный `scan` (в том числе после
рестарта) никогда не перезаписывает уже сохранённый оригинал. Зимой/летом/при
`restore` меняются только `applied_state` и биом в мире.

## Как продолжается сканирование после рестарта

* Обход идёт кольцами чанков от `(0,0)` наружу; номер следующего кольца лежит в
  `meta.scan_ring` и обновляется после каждого кольца (коммит в базу).
* При старте плагин читает `meta.scan_ring` и продолжает с этого кольца; чанки,
  которые уже есть в таблице `chunks`, пропускаются без загрузки (проверка по
  in-memory множеству, ничего с диска не читается).
* Если карта была пройдена до конца (`meta.scan_complete=1`), следующий `scan start`
  идёт с нулевого кольца и просто досканирует новые чанки — уже известные не трогает.
* `empty-rings-to-stop` подряд пустых колец = конец карты мира (не генерируем).

## Погода и лёд

Погода в игре зависит от `temperature` биома: `> 0.15` — дождь, ниже — снег,
`hasPrecipitation=false` — осадков нет. Поэтому зима ставит снежные биомы (снег).
Плагин меняет только биомы, блоки не трогает: `blue_ice` и `packed_ice` не тают
никогда, обычный `ice` тает от блокового света, а не от температуры, а снежные слои
нарастают и сходят сами. Ледники и пики (`frozen_peaks`, `jagged_peaks`,
`snowy_slopes`, `ice_spikes`) входят в сезонный цикл по таблице.

## Сборка

```sh
./build.sh     # docker, JDK 25, paper-api 26.3 из lib/, драйвер SQLite вшивается в jar
```
