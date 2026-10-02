# PapersDelight 格式兼容

Farmersdelight-Plugin-Pro 采用与 PapersDelight 相近的配置分组，并提供其公开厨锅、砧板配方格式的兼容入口。本页区分文件格式与游戏机制：能够读取一条配方，不代表已经实现其他插件的流体、容器或方块行为。

格式依据为 PapersDelight 1.2.0 的配置文件、公开 API、提供的内容包配方，以及[官方配方文档](https://www.yuque.com/shimamuratako/papersdelight/lylhov4b35d635aw)。以下示例为本项目独立编写，使用原版物品；不包含 PapersDelight 的代码、授权验证或付费内容包。

## 内容包位置

默认配方放在 CraftEngine 内容包内：

```text
plugins/CraftEngine/resources/farmersdelight/
  pack.yml
  configuration/
    recipes/
      cooking_pot_recipes.yml
      cutting_board_recipes.yml
      food_groups.yml
```

其他已启用内容包也可在其 `configuration/` 目录下放置配方 YAML。文件名和子目录用于组织文件；真正决定配方含义的是顶层配置区块和配方的 `type`。

PapersDelight 配方入口为 `papersdelight_recipes`，与 CraftEngine 自身的 `recipes` 区块分别加载。不要将这两种区块互相替换。

## 已公开的配方格式

每条配方以唯一 ID 为键。建议使用 `命名空间:路径`，例如 `example:cooking/carrot`。同一个文件可同时包含厨锅、砧板及其他内容包区块。

| 类型 | 必填字段 | 可选字段 | 对应机制 |
| --- | --- | --- | --- |
| `cooking` | `ingredients`、`result` | `container`、`time`、`experience` | 厨锅烹饪 |
| `cutting` | `ingredient`、`results` | `tools`、`sound` | 砧板切割 |
| `info` | `item` | `description` | 物品获取说明，纯展示 |
| `fluid_filling` | `fluid`、`amount`、`empty_input`、`filled_result` | `time`、`priority` | 从 FluidCore 储罐灌装物品 |
| `fluid_emptying` | `fluid`、`amount`、`filled_input`、`empty_result` | `time`、`priority` | 把物品中的流体排入 FluidCore 储罐 |
| `soaking` | `ingredient`、`fluid`、`amount`、`result` | `time`、`consume_fluid`、`priority` | 在 FluidCore 储罐流体中浸泡物品 |

### 厨锅

```yaml
papersdelight_recipes:
  example:cooking/carrot:
    type: cooking
    ingredients:
      - minecraft:carrot
      - items:
          - minecraft:potato
          - minecraft:beetroot
    result:
      id: minecraft:baked_potato
      count: 1
    container: none
    time: 120
    experience: 0.1
```

这是演示解析结构的配方；可根据服务器玩法修改成品和食材。

| 字段 | 格式 | PapersDelight 默认值及单位 |
| --- | --- | --- |
| `ingredients` | 非空列表，每项为字符串或 `{items: [...]}` | 普通厨锅最多 6 个原料槽 |
| `result` | 物品 ID 字符串，或 `{id: 物品ID, count: 数量}` | `count` 为 1 |
| `container` | 容器物品 ID | 省略时无显式容器要求 |
| `time` | 整数 | 200 tick；20 tick = 1 秒 |
| `experience` | 小数 | 0.0 |

`container: none` 为本项目已有扩展，用于显式关闭容器要求。容器自动推断、容器归还及自定义大厨锅仍遵循 Farmersdelight-Plugin-Pro 的对应配置。

### 砧板

```yaml
papersdelight_recipes:
  example:cutting/carrot:
    type: cutting
    ingredient:
      items:
        - minecraft:carrot
        - minecraft:golden_carrot
    tools:
      - "#minecraft:axes"
      - minecraft:shears
    results:
      - id: minecraft:orange_dye
        count: 2
      - id: minecraft:wheat_seeds
        chance: 0.25
    sound:
      id: minecraft:block.wood.break
      volume: 0.8
      pitch: 1.0
```

| 字段 | 格式 | PapersDelight 默认值 |
| --- | --- | --- |
| `ingredient` | 字符串，或 `{items: [...]}` 多选一 | 必填；每次处理一个输入物品 |
| `results` | 非空列表，每项为字符串或 `{id, count, chance}` | `count: 1`、`chance: 1.0` |
| `tools` | 物品/标签字符串列表 | 省略或为空时使用 `cutting_board.default_tools` |
| `sound` | 音效 ID 字符串，或 `{id, volume, pitch}` | 字符串形式的 `volume` 和 `pitch` 均为 1.0 |

PapersDelight 的产出数量小于 1 时按 1 处理；概率小于 0 或大于 1 时按边界处理。格式兼容层按此规则读取其 `count` 与 `chance`，并检查无效数值。

注意：配方对象中的音效键为 `sound.id`；主配置里的音效对象使用 `sound` 子字段，例如 `cutting_board.sounds.place_item.sound`。

### 信息展示

PapersDelight 的 `info` 只提供获取说明，不消耗材料、不产出物品：

```yaml
papersdelight_recipes:
  example:info/carrot:
    type: info
    item: minecraft:carrot
    description:
      - "<gray>可通过种植获取。"
```

`description` 是文本行列表，默认空列表；支持 MiniMessage 和 `<lang:...>` 翻译键。本插件将这类条目显示为配方菜单中的信息卡，不作为厨锅配方执行。

## 物品、标签与候选项

| 表达式 | 含义 | 示例 |
| --- | --- | --- |
| `minecraft:物品` | 原版物品 | `minecraft:carrot` |
| `命名空间:物品` | CraftEngine 自定义物品 | `example:carrot_slice` |
| `#命名空间:标签` | 原版或自定义物品标签 | `"#minecraft:planks"` |
| `advtag:命名空间:标签` | PapersDelight 的高级标签表达式 | `advtag:example:vegetables` |
| `{items: [...]}` | 候选项中的任意一项符合即可 | `{items: [minecraft:carrot, minecraft:potato]}` |

以 `#` 开头的值应加引号，避免被 YAML 视为注释。候选项是“多选一”，不表示需要同时投入列表中的全部物品。重复候选项不会提高材料数量；需要两份相同食材时，应在 `ingredients` 中写两项。

PapersDelight 的匹配表达式会去除两端空格并转为小写。其 `advtag:` 由独立高级标签解析器处理，与普通 `#` 标签不是同一种注册方式。仅保留表达式不能恢复未提供的标签成员；迁移时应同时提供相应食材分组或标签定义，并核对最终展开结果。

内容包高级标签使用单独的 `advanced_tags` 区块，`values` 中可以引用其他高级标签：

```yaml
advanced_tags:
  example:roots:
    values:
      - minecraft:carrot
      - minecraft:potato
  example:vegetables:
    values:
      - advtag:example:roots
      - minecraft:beetroot
```

高级标签应在所有内容包定义收集后展开。未定义的引用、循环引用和空成员需要输出诊断，不能自动当成原版物品或普通标签。

## 流体灌装、排空与浸泡

这些配方的字段已按提供的 PapersDelight 1.2.0 内容包核对。加工使用可选的 FluidCore 插件，并且只访问其实际注册的储罐。流体数量为整数 **mB**：一桶为 1000 mB，一瓶为 250 mB；`time` 为 tick。

```yaml
papersdelight_recipes:
  example:fluid_emptying/water_bucket:
    type: fluid_emptying
    fluid: minecraft:water
    amount: 1000
    filled_input: minecraft:water_bucket
    empty_result: minecraft:bucket

  example:fluid_filling/water_bucket:
    type: fluid_filling
    fluid: "#c:water"
    amount: 1000
    empty_input: minecraft:bucket
    filled_result: minecraft:water_bucket

  example:soaking/sponge:
    type: soaking
    ingredient: minecraft:sponge
    fluid: "#c:water"
    amount: 1000
    result: minecraft:wet_sponge
    time: 20
    consume_fluid: true
```

`fluid` 为流体 ID 或带 `#` 的流体标签，不是物品标签。优先使用 FluidCore 中实际定义的 `c:water`、`c:milk`、`c:lava`；这些标签没有声明时才兼容映射到库自带的对应标签。`c:honey` 等其他标签需要服务器自行注册流体和标签，缺失时不执行配方。

FluidCore 默认没有蜂蜜流体。需要使用 `"#c:honey"` 的服务器，可在已启用内容包的配置中添加下面的流体元数据；这是本项目独立示例，不引入其他插件的素材。如果服务器已经注册了 `minecraft:honey`，应为已有定义补标签，避免重复注册同名流体：

```yaml
"fluidcore:fluids":
  minecraft:honey:
    display-name: "蜂蜜"
    tags: [c:honey]
    color: "#FFE8AA2B"
```

定义让配方能够识别蜂蜜，不会自动往储罐里生成蜂蜜。仍需通过服务器自己的流体容器、生产机制或管理工具向储罐添加该流体。

`consume_fluid: false` 仅适用于浸泡：仍要求储罐中有完整的指定数量，但完成加工时保留流体。灌装和排空始终转移完整数量。数量不足、储罐容量不足、库存没有成品空间或物品流体数据受保护时，整条加工取消，不提前消耗、不把溢出成品丢到地面。创造模式同样实际替换输入物品。

等待期间不保留流体事务。时间结束后，应再次核对玩家、原槽物品、距离和储罐所有权，再同步提交流体与物品变化。切换物品、离开储罐或卸载储罐应取消加工。

本插件还允许灌装或排空省略显式产出，直接采用 FluidCore 容器处理器返回的物品，并保留其流体组件。显式固定产出的配方不适用于带额外流体组件的容器转换，也不会丢弃输入容器中剩余的流体。

固定产出的排空配方也可使用流体标签。如果输入物品没有可验证的 FluidCore 容器处理器，标签必须只对应一个已注册流体；标签包含多种流体时拒绝加工，不凭顺序选择其中一种。缺失流体或空标签同样不执行。

PapersDelight 内容包中的 `papersdelight:jug`、`papersdelight:jug_item` 和 `libuid:fluid_container` 是不同的方块、物品行为及存储格式。支持上述配方字段不能自动将其变成 FluidCore 储罐，也不能将未经验证的旧流体数据视为空桶。已有壶需要独立行为适配或改用 FluidCore 的 `fluidcore:tank`、`fluidcore:container` 设置；迁移前保留存档备份。

本轮保留物品行为与容器设置的兼容入口，但不自动注册 `papersdelight:jug` 方块行为。方块行为工厂不能安全修改已构造的掉落表，直接别名会有破坏壶后丢失流体的风险。采用 FluidCore 储罐时，应显式把方块行为改为 `fluidcore:tank`，同时修改其掉落表。

以下为独立编写的完整储罐定义，放在已启用的 CE 内容包 `configuration/` 中即可。它使用原版铁块模型，容量为 16000 mB，掉落表只有一个自身物品条目；先固定数量为 1，再保存储罐数据：

```yaml
items:
  example:recipe_tank:
    material: minecraft:iron_block
    data:
      item_name: "<white>配方储罐"
      max_stack_size: 1
    model: minecraft:block/iron_block
    behavior:
      type: block_item
      block:
        settings:
          hardness: 2.0
          resistance: 6.0
        behavior:
          type: fluidcore:tank
          capacity: 16000
        loot:
          pools:
            - rolls: 1
              entries:
                - type: item
                  item: example:recipe_tank
                  functions:
                    - type: set_count
                      count: 1
                    - type: fluidcore:preserve_tank
        state:
          auto_state: solid
          model: minecraft:block/iron_block
```

`fluidcore:preserve_tank` 只能放在一个自身掉落条目上，执行时物品数量必须为 1。不要额外添加另一条自身掉落、把数量改成多个，或在保存数据之后再倍增数量。该定义只负责新 FluidCore 储罐；它不转换原 PapersDelight／LibUID 的保存内容。

带 `fluidcore:tank_data` 保存数据的储罐物品可以原样重新放置，但不会被这些流体配方当作原料或成品加工。储罐方块保存数据与便携容器的 `container_data` 不同，避免将前者当成空瓶、转换丢失内容或从成品快照复制流体。

## Farmersdelight-Plugin-Pro 的扩展

本插件保留精准和模糊配方、理想配比、等效食材、调味品、配方优先级、自定义厨锅组、物品组件及容器处理等已有功能。相关扩展不会要求安装 PapersDelight，也不会引入激活码。

已有的 `cooking_pot_recipes`、`cutting_board_recipes`、`custom_cooking_pot_recipes` 格式属于兼容输入。默认的新配方采用上述内容包结构；已有菜单操作和森罗菜谱自动投料仍由 Farmersdelight-Plugin-Pro 处理。

## 配置兼容边界

主配置以 `lang`、`heat_sources`、`cooking_pot`、`cutting_board`、`skillet`、`stove`、`pet_food`、`recipe_book` 等下划线分组组织。原有配置迁移后保留本插件独有选项，显式设置应优先于新版本默认值。

`heat_sources` 是规则列表，支持原版 `material`、自定义方块 `ce_block` 和自定义方块标签 `ce_block_tag`。`material` 与 `ce_block` 可附带 `states` 条件；当前 `ce_block_tag` 只匹配标签，不应用 `states`。`heat_source` 和 `conductor` 分别控制热源与导热判断。`tray` 仅保留配置并在启用时输出未支持提示，尚未实现 PapersDelight 的托盘条件判断。

| PapersDelight 的功能/字段 | 格式参考与本插件的边界 |
| --- | --- |
| `license` | 不需要，也不引入其收费验证机制 |
| `config-version` | 其内部配置版本号，不作为本插件版本号 |
| `particle_throttle` | 对应性能选项需要明确映射；读取字段不能代替实际节流 |
| `nourishment_effect`、`comfort_effect` | 本插件已有相近效果；启停、显示和持续时间按本插件配置生效 |
| `garlic_effect` | 本插件未实现的效果不得以读取配置冒充支持 |
| `stats` | 其 SQLite 统计、PlaceholderAPI 计数及跨下线效果保存不是单纯字段重命名 |
| `villager.yml` | 村民交易、拾取、繁殖和自定义作物收获需要对应行为实现 |
| `insertable_tools.yml` | 工具插入与展示参数需要对应展示器；不是配方定义 |

PapersDelight 的整份 CraftEngine 内容包还可能使用其专属方块行为、物品事件函数、进度配置及其他附属入口。配方格式兼容不自动注册这些入口；使用前应确认日志中没有未知行为、未知函数或未解析标签。

## 已适配的主要行为入口

这些入口使用本插件已有的加工机制，并在加载内容包之前注册。遇到其他插件已经注册同一个 ID 时，不覆盖其行为。

| ID | 适配内容与范围 |
| --- | --- |
| `papersdelight:cooking_pot` | 使用本插件厨锅；支持 `support: int` 的 0=无支撑显示、1=托盘、2=提手，保留原字符串状态接口 |
| `papersdelight:cutting_board` | 使用本插件砧板；读取 `has_comparator` 控制比较器输出 |
| `papersdelight:skillet` | 使用本插件煎锅；支持布尔 `support` 状态 |
| `papersdelight:skillet_item` | 使用本插件手持煎锅，并保留 `block` 指定的 CE 方块放置能力 |
| `papersdelight:stove` | 使用 `lit` 布尔状态与其 `sound`；原内容包事件负责点火及熄火，单独高温行为需要另外实现 |
| `papersdelight:nourishment_effect`、`papersdelight:comfort_effect` | 对接本插件效果；`duration` 按 tick 读取，600 tick 对应 30 秒 |

本插件效果计时精度为秒，非整秒的 tick 持续时间向上取整到下一秒。炉灶的声效调度与粒子节流使用本插件配置，尚未将 PapersDelight 方块行为中的 `interval`、`volume`、`pitch` 完整转换为同样的独立调度。

以下入口仍需要独立玩法实现或逐类核对，当前不会注册一个空行为来掩盖缺失功能：

```text
papersdelight:basket
papersdelight:advanced_crop
papersdelight:roped_crop
papersdelight:double_crop
papersdelight:grant_advancement
papersdelight:remove_random_effect
papersdelight:organic_compost
papersdelight:rich_soil
papersdelight:farmland
papersdelight:rope
papersdelight:rope_block
papersdelight:skewer_item
papersdelight:high_temperature
papersdelight:pairable_block
papersdelight:horizontal_double_block_item
papersdelight:horizontal_double_block
papersdelight:wild_rice
dumplings_delight:garlic_effect
```

因此，本轮支持的配方与主要加工入口可以使用，但不能直接把整个 PapersDelight 内容包视为完全兼容。含有未实现行为的方块可能被 CraftEngine 拒绝加载，应调整该行为或继续使用本插件已经加载的对应资源。上述适配也不迁移 PapersDelight 的旧方块实体、库存或 LibUID 流体 NBT；已有世界的内容需要单独确认存档迁移规则。

## 其他配方类型与行为边界

PapersDelight 1.2.0 的公开 API 还保留 `single`、`decomposition` 类型名。所提供的内容包包含 `decomposition` 的 `ingredient`、`result` 和 `catalysts` 字段，但方块分解的世界条件需要对应游戏机制；`single` 尚未在该内容包中找到完整配方样例。

对于本插件尚未实现或尚未验证的类型，应保留源文件、输出带配方 ID 和来源的诊断，并继续加载可用配方。不会将其退化成厨锅或砧板加工。

本轮 `info` 已按信息卡展示，`single` 尚未适配。兼容声明仅覆盖已经实现并通过验证的条目。

## 本轮验证

1.1.0 通过 493 项自动测试和仓库语言、资源、配置及 API 边界检查。使用 CraftEngine 26.10 快照与 FluidCore，在 Paper 26.3 和 Folia 26.2 各通过 52 项服内原生检查，覆盖实际事件分发与权限拒绝、流体事务、失败回滚、延迟取消、编辑保存及储罐掉落后重新放置恢复内容。服内检查使用原生玩家与背包测试对象，没有连接游戏客户端；完整内容包的客户端模型及其他未适配玩法仍应单独验收。
