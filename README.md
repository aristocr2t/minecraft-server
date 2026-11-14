# minecraft-server

Paper **26.3** (Java 25) в docker, ~10 игроков.

> Гайд для игроков: **GUIDE.md** — как зайти, приват, экономика, порталы, мини-игры.

## deploy

1. `./build-server` — скачать свежий Paper 26.3 в `server/server.jar` (бинарник в git не хранится).
2. `./build-plugins` — скачать плагины из `plugins.txt` (Modrinth) в `server/plugins`.
3. `echo "eula=true" > server/eula.txt` — один раз, если файла нет (Mojang EULA).
4. `./deploy` — собрать образ и поднять контейнер. Переменные берутся из `env/default.env`, поверх — из `env/$ENV.env` (не обязателен).
5. `./setup` — один раз после первого запуска: группы прав и спавн на 0 0 (см. ниже).

Порты: 25565/tcp — игра, 8100/tcp — 3D-карта, 24454/udp — голосовой чат.

## rcon

`./rcon` (пароль берётся из `env/production.env` → `RCON_PASSWORD`, порт 25575, наружу не публикуется).

## плагины (server/plugins)

| плагин | зачем |
| --- | --- |
| BlueMap 5.29 | 3D-карта в браузере `http://<host>:8100` — можно летать по биомам |
| VaultUnlocked 2.20.3 | мост экономики между плагинами (Vault API) |
| EssentialsC 4.3.5 | home/tpa/warp/kit, `/bal`, `/pay`, `/shop` (магазин всех предметов), `/ah` (аукцион) |
| UJobs 1.0.8 | 13 работ: копай/фарми/охоться/рыбачь — деньги и опыт капают автоматически |
| LuckPerms 5.5.71 | права и группы (storage yaml, файлы в git) |
| Advanced Portals 2.8.0 | водяные порталы: блок-триггер `WATER`, права у vip и played24h |
| PlaytimeReward 1.0 | через 24 часа игры выдаёт группу `played24h` |
| AuthMe 6.0.1 | пароли на offline-сервере (`online-mode=false`) |
| SkinsRestorer 15.12.6 | скины игроков в offline-режиме |
| WorldEdit 7.4.6 / WorldGuard 7.0.21 | стройка и защита регионов |
| Chunky 1.5.66 | предгенерация чанков: `chunky radius 2000`, затем `chunky start` |
| SeasonsPlus 1.4.2 | сезоны и температура тела: перегрев/переохлаждение, зимой стражи, летом без дождя |
| BoatRacing 26.2.2 | гонки на лодках по льду, трассы и лидерборды |
| Dragon Egg Respawns 1.1.0 | новое яйцо дракона после каждого убийства |
| DragonEgg 3.1.0 | яйцо можно оживить и вырастить дракона |
| EvenMoreFish 2.5.3 + EMFFishStew 1.0.3 | соревновательная рыбалка и турниры |
| HungerGames 1.2 | классические голодные игры: `/hg arena ...` |
| RoundHaven 0.12.0 | ивенты в отдельном мире: `/rh` — Spleef, Sumo, Archer Rush, Chaos; инвентари игроков сохраняются и возвращаются |
| Simple Voice Chat 2.6.27 | проксимити-голос, нужен мод у клиента |

Конфиги плагинов генерируются при первом запуске и в git не попадают. Jar-ы тоже не в git:
список версий — `plugins.txt`, скачивание — `./build-plugins` (`seasonsbar` собирается из `seasonsbar/`).
Исключение: `server/plugins/BlueMap/core.conf` (`accept-download: true`, иначе BlueMap не рендерит).

## экономика

Деньги единые: UJobs платит через Vault, EssentialsC хранит баланс (`/bal`) и продаёт в `/shop`.
Стартовый капитал новичкам — `eco` / настройка `starting-balance` в `server/plugins/EssentialsC/config.yml`.
Цены и список работ — `server/plugins/UJobs/jobs.yml`, магазин — `server/plugins/EssentialsC/shop/*.yml`.

## рельеф: Tectonic

`datapacks/tectonic-datapack-3.0.29.zip` — worldgen-датапак: высокие каньоны, реки с водопадами.
`./deploy` копирует `datapacks/*.zip` в `server/world/datapacks/` перед стартом, иначе новая карта
создастся без него. Датапак применяется только к новым чанкам, менять его на живой карте бессмысленно.

## хардкор: телепорты и водяные порталы

Правила задаёт `./setup` (идемпотентен, но спавн строит один раз — на чистом мире):

| группа | что может |
| --- | --- |
| `default` (все) | никаких телепортов: `/tpa`, `/home`, `/spawn`, `/warp`, `/back`, `/rtp` выключены |
| `played24h` | + водяные порталы (выдаётся автоматически после 24 часов игры, PlaytimeReward) |
| `vip` | + водяные порталы (телепортов нет ни у кого) |

VIP выдаётся руками: `./rcon "lp user <ник> parent add vip"`.

Сложность: `difficulty=hard`, `hardcore=false` (смерть не окончательная), `natural_health_regeneration=false`
(здоровье только едой и зельями), вещи после смерти теряются, телепортов нет — весь мир проходится ногами.
В 26.3 геймрулы переименованы в snake_case, поэтому откат выглядит так:
`./rcon "gamerule natural_health_regeneration true"`.

Как сделать водяной портал (Advanced Portals, блок-триггер = вода):
1. получить топор: `/portal selector`;
2. построить рамку и залить её водой;
3. выделить рамку топором и создать портал: `/portal create <имя> <destination>`;
4. точки назначения делает админ: `/desti create <имя>` (стоя на месте), `/desti list`.

Права LuckPerms лежат в `server/plugins/LuckPerms/yaml-storage/` (storage = yaml),
плагинные — в `server/plugins/AdvancedPortals/config.yaml` и `server/plugins/PlaytimeReward/config.yml`.

## спавн

`./setup` находит поверхность на 0 0 и строит площадь 49×49: плита, стены по периметру,
4 башни с фонарями, 4 фонтана, дорожки к центру и подиум. Точка возрождения — `0 y 0`,
`spawnRadius 2`, зона защищена `spawn-protection` из `server.properties`.

## мини-игры

- HungerGames: `/hg arena create <имя>` + выделение арены палочкой (`/hg arena …`), арены и лут в `server/plugins/HungerGames`.
- RoundHaven: арена = отдельный временный мир, создаётся из панели `/rh`; сценарий Spleef выбирается при создании раунда.

Игры удобно ставить в отдельный мир/регион, чтобы не ломать основной (WorldGuard + `/rh` это позволяют).

## BlueMap

- первая отрисовка мира — долго и грузит CPU, дальше рендерятся только изменённые регионы;
- `/bluemap` в консоли или rcon: `render` / `reload` / `stop`;
- файлы карты лежат в `server/bluemap` — они большие, при бэкапе их можно не сохранять.

## память

В `docker-compose.yml` heap 6G (`-Xmx6G`) — с запасом на 10 игроков, BlueMap и мини-игры. Менять там же.
