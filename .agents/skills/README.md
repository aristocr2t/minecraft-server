# Skills в репозитории

Проектные скиллы Pi (спецификация Agent Skills). Pi находит их в `.agents/skills/` и подхватывает после `/reload`.

| скилл | источник | зачем |
| --- | --- | --- |
| `minecraft-builder-skill` | https://github.com/mattzh72/minecraft-builder-skill (Apache-2.0) | постройки как код: JS-рецепты -> ванильный `.nbt` + 3D-превью через Lodestone |
| `minecraft-worldedit-ops` | https://github.com/Jahrome907/minecraft-agent-skills (MIT) | безопасные операции WorldEdit под 26.x |
| `minecraft-commands-scripting` | там же | команды, селекторы, execute |
| `minecraft-world-generation` | там же | биомы, структуры, worldgen |
| `minecraft-server-admin` | там же | настройка и разбор проблем сервера |
| `minecraft-datapack` | там же | датапаки, структуры, лут-таблицы |
| `minecraft-essentials-ops` | там же | команды и конфиги EssentialsX |

## Зависимости builder-скилла

`node_modules` в git не лежит. Перед первым использованием:

```bash
cd .agents/skills/minecraft-builder-skill && npm install --omit=dev
```

Сборка постройки и превью:

```bash
node scripts/run-build.mjs my-recipe.mjs --out-dir builds/my-build
node scripts/preview.mjs --nbt builds/my-build/my-build.nbt --no-open
```

Полученный `.nbt` кладётся в датапак (`data/<ns>/structure/`) и ставится на сервере командой
`/place structure <ns:id> <x> <y> <z>` — консолью через `./rcon`, без игрока.
