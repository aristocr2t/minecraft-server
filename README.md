# minecraft-server

Paper **26.3** (Java 25) в docker, ~10 игроков.

## deploy

1. `./build-server` — скачать свежий Paper 26.3 в `server/server.jar` (уже в репозитории).
2. `echo "eula=true" > server/eula.txt` — один раз, если файла нет (Mojang EULA).
3. `./deploy` — собрать образ и поднять контейнер. Переменные берутся из `env/default.env`, поверх — из `env/$ENV.env` (не обязателен).

Порты: 25565/tcp — игра, 8100/tcp — 3D-карта, 24454/udp — голосовой чат.

## rcon

`./rcon` (пароль `mcrcon`, порт 25575, наружу не публикуется).

## плагины (server/plugins)

| плагин | зачем |
| --- | --- |
| BlueMap 5.29 | 3D-карта в браузере `http://<host>:8100` — можно летать по биомам |
| VaultUnlocked 2.20.3 | мост экономики между плагинами (Vault API) |
| EssentialsC 4.3.5 | home/tpa/warp/kit, `/bal`, `/pay`, `/shop` (магазин всех предметов), `/ah` (аукцион) |
| UJobs 1.0.8 | 13 работ: копай/фарми/охоться/рыбачь — деньги и опыт капают автоматически |
| LuckPerms 5.5.71 | права и группы |
| AuthMe 6.0.1 | пароли на offline-сервере (`online-mode=false`) |
| SkinsRestorer 15.12.6 | скины игроков в offline-режиме |
| WorldEdit 7.4.6 / WorldGuard 7.0.21 | стройка и защита регионов |
| Chunky 1.5.66 | предгенерация чанков: `chunky radius 2000`, затем `chunky start` |
| HungerGames 1.2 | классические голодные игры: `/hg arena ...` |
| RoundHaven 0.12.0 | ивенты в отдельном мире: `/rh` — Spleef, Sumo, Archer Rush, Chaos; инвентари игроков сохраняются и возвращаются |
| Simple Voice Chat 2.6.27 | проксимити-голос, нужен мод у клиента |

Конфиги плагинов генерируются при первом запуске и в git не попадают — в репозитории только jar-ы.
Исключение: `server/plugins/BlueMap/core.conf` (`accept-download: true`, иначе BlueMap не рендерит).

## экономика

Деньги единые: UJobs платит через Vault, EssentialsC хранит баланс (`/bal`) и продаёт в `/shop`.
Стартовый капитал новичкам — `eco` / настройка `starting-balance` в `server/plugins/EssentialsC/config.yml`.
Цены и список работ — `server/plugins/UJobs/jobs.yml`, магазин — `server/plugins/EssentialsC/shop/*.yml`.

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
