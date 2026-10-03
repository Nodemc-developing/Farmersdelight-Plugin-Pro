# 配置、配方与内容包

本页介绍 Farmersdelight-Plugin-Pro 的配置、内容包配方、食材表达式和迁移规则。配方由已启用的 CraftEngine 内容包加载，游戏内编辑会写回来源节点。文件能够解析与玩法能够执行需要分别确认，未实现的类型会保留原文件并输出诊断。

以下示例使用原版物品。兼容入口的技术键名保持不变；本插件自己的扩展字段与适用范围在对应段落中说明。

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

兼容配方入口为 `papersdelight_recipes`，与 CraftEngine 自身的 `recipes` 区块分别加载。不要将这两种区块互相替换。

## 配方格式

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

| 字段 | 格式 | 默认值及单位 |
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

| 字段 | 格式 | 默认值 |
| --- | --- | --- |
| `ingredient` | 字符串，或 `{items: [...]}` 多选一 | 必填；每次处理一个输入物品 |
| `results` | 非空列表，每项为字符串或 `{id, count, chance}` | `count: 1`、`chance: 1.0` |
| `tools` | 物品/标签字符串列表 | 省略或为空时使用 `cutting_board.default_tools` |
| `sound` | 音效 ID 字符串，或 `{id, volume, pitch}` | 字符串形式的 `volume` 和 `pitch` 均为 1.0 |

兼容输入的产出数量小于 1 时按 1 处理；概率小于 0 或大于 1 时按边界处理。解析时同时检查无效数值。

注意：配方对象中的音效键为 `sound.id`；主配置里的音效对象使用 `sound` 子字段，例如 `cutting_board.sounds.place_item.sound`。

### 信息展示

`info` 只提供获取说明，不消耗材料、不产出物品：

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
| `advtag:命名空间:标签` | 独立高级标签表达式 | `advtag:example:vegetables` |
| `{items: [...]}` | 候选项中的任意一项符合即可 | `{items: [minecraft:carrot, minecraft:potato]}` |

以 `#` 开头的值应加引号，避免被 YAML 视为注释。候选项是“多选一”，不表示需要同时投入列表中的全部物品。重复候选项不会提高材料数量；需要两份相同食材时，应在 `ingredients` 中写两项。

匹配表达式会去除两端空格并转为小写。`advtag:` 由独立高级标签解析器处理，与普通 `#` 标签不是同一种注册方式。仅保留表达式不能恢复未提供的标签成员；迁移时应同时提供相应食材分组或标签定义，并核对最终展开结果。

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

加工使用可选的 FluidCore 插件，并且只访问其实际注册的储罐。FluidCore 方块实体需要 CraftEngine 26.10。流体数量为整数 **mB**：一桶为 1000 mB，一瓶为 250 mB；`time` 为 tick。

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

`fluid` 为流体 ID 或带 `#` 的流体标签，不是物品标签。优先使用 FluidCore 中实际定义的 `c:water`、`c:milk`、`c:lava`、`c:honey`；这些标签没有声明时才兼容映射到库自带的对应标签。其他未注册流体或标签不执行配方。

FluidCore 默认注册蜂蜜及原版桶瓶处理。服务器可添加或覆写流体元数据；已有定义只需补标签，避免重复注册同名流体：

```yaml
"fluidcore:fluids":
  minecraft:honey:
    display-name: "蜂蜜"
    tags: [c:honey]
    color: "#FFE8AA2B"
```

定义让配方能够识别蜂蜜，不会自动往储罐里生成蜂蜜。仍需通过服务器自己的流体容器、生产机制或管理工具向储罐添加该流体。

未显式指定 `time` 时，浸泡的默认值是 **0 tick**。自带包包含 22 条基础配方及 2 条水桶配方；部分自带配方明确设置等待时间，不受缺省值影响。

流体配方的原料可使用 ID、标签、多选及 `components` / `exact-components` 条件。组件条件由 FluidCore 的类型接口匹配，不仅用于显示；普通厨锅和砧板尚未支持的匹配字段会拒绝该配方并指出字段，不把它们当作普通 ID 配方执行。结果的无效组件或损坏 NBT 同样拒绝加载。

`consume_fluid: false` 仅适用于浸泡：仍要求储罐中有完整的指定数量，但完成加工时保留流体。灌装和排空始终转移完整数量。数量不足、储罐容量不足、库存没有成品空间或物品流体数据受保护时，整条加工取消，不提前消耗、不把溢出成品丢到地面。创造模式同样实际替换输入物品。

等待期间不保留流体事务。时间结束后，应再次核对玩家、原槽物品、距离和储罐所有权，再同步提交流体与物品变化。切换物品、离开储罐或卸载储罐应取消加工。

本插件还允许灌装或排空省略显式产出，直接采用 FluidCore 容器处理器返回的物品，并保留其流体组件。显式固定产出的配方不适用于带额外流体组件的容器转换，也不会丢弃输入容器中剩余的流体。

排空读取输入容器实际持有的流体及组件，再检查 ID 或标签条件。没有可验证的容器处理器时不能从标签猜测流体。带组件的流体不能通过固定成品声明丢弃组件，输入容器剩余内容也不能丢失。

新加载的 `papersdelight:jug`、`papersdelight:jug_item` 和 `libuid:fluid_container` 配置会适配为 FluidCore 的类型接口。物品设置提供缺省容量，方块行为显式容量优先；拆除自身掉落必须固定为一件并携带流体。适配在 CE 模板展开后执行，不修改原配置文件。

**不转换旧流体库的世界与物品存档**。识别到旧载荷时保留并拒绝覆盖，不把旧罐视为空罐；先备份并在原环境处理已有内容。无法安全确定自身掉落条目的新配置会明确报错。

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

`fluidcore:preserve_tank` 只能放在一个自身掉落条目上，执行时物品数量必须为 1。不要额外添加另一条自身掉落、把数量改成多个，或在保存数据之后再倍增数量。该定义只负责新 FluidCore 储罐；它不转换其他方块行为或流体库的保存内容。

带 `fluidcore:tank_data` 保存数据的储罐物品可以原样重新放置，但不会被这些流体配方当作原料或成品加工。储罐方块保存数据与便携容器的 `container_data` 不同，避免将前者当成空瓶、转换丢失内容或从成品快照复制流体。

## Farmersdelight-Plugin-Pro 的扩展

本插件保留精准和模糊配方、理想配比、等效食材、调味品、配方优先级、自定义厨锅组、物品组件及容器处理等已有功能。扩展项与其他配方一起从内容包加载和编辑。

已有的 `cooking_pot_recipes`、`cutting_board_recipes`、`custom_cooking_pot_recipes` 格式属于兼容输入。默认的新配方采用上述内容包结构；已有菜单操作和森罗菜谱自动投料仍由 Farmersdelight-Plugin-Pro 处理。

## 配置兼容边界

主配置以 `lang`、`heat_sources`、`cooking_pot`、`cutting_board`、`skillet`、`stove`、`pet_food`、`recipe_book` 等下划线分组组织。原有配置迁移后保留本插件独有选项，显式设置应优先于新版本默认值。

`heat_sources` 是规则列表，支持原版 `material`、自定义方块 `ce_block` 和自定义方块标签 `ce_block_tag`，并检查配置的 `states` 条件。`heat_source`、`conductor` 分别控制热源与导热判断；`tray: true` 要求对应托盘，托盘定义由同一热源规则核对。

| 兼容输入的功能/字段 | 当前处理范围 |
| --- | --- |
| `license` | 不参与本插件运行，无对应配置要求 |
| `config-version` | 兼容输入的配置版本号，不作为本插件版本号 |
| `particle_throttle` | 按锅/炉灶密度降低粒子与环境音机会；同时执行区块和单连接预算 |
| `nourishment_effect`、`comfort_effect` | 本插件已有相近效果；启停、显示和持续时间按本插件配置生效 |
| `garlic_effect` | 本插件未实现的效果不得以读取配置冒充支持 |
| `stats` | 成功提交后的累计统计、异步 SQLite 和只读缓存的 PAPI；持续效果存档沿用原系统 |
| `villager.yml` | 村民交易、拾取、食物点和自定义作物收获补种 |
| `insertable_tools.yml` | 对接已有工具插入展示器，不是配方定义 |

其他 CraftEngine 内容包可能使用单独注册的方块行为、物品事件函数、进度配置及附属入口。配方格式兼容不自动注册这些入口；使用前应确认日志中没有未知行为、未知函数或未解析标签。

## 已适配的主要行为入口

这些入口使用本插件已有的加工机制，并在加载内容包之前注册。遇到其他插件已经注册同一个 ID 时，不覆盖其行为。

| ID | 适配内容与范围 |
| --- | --- |
| `papersdelight:cooking_pot` | 使用本插件厨锅；支持 `support: int` 的 0=无支撑显示、1=托盘、2=提手，保留原字符串状态接口 |
| `papersdelight:cutting_board` | 使用本插件砧板；读取 `has_comparator` 控制比较器输出 |
| `papersdelight:skillet` | 使用本插件煎锅；支持布尔 `support` 状态 |
| `papersdelight:skillet_item` | 使用本插件手持煎锅，并保留 `block` 指定的 CE 方块放置能力 |
| `papersdelight:stove` | 使用 `lit` 布尔状态与其 `sound`；原内容包事件负责点火及熄火，高温由独立行为配置 |
| `papersdelight:nourishment_effect`、`papersdelight:comfort_effect` | 对接本插件效果；`duration` 按 tick 读取，600 tick 对应 30 秒 |

本插件效果计时精度为秒，非整秒的 tick 持续时间向上取整到下一秒。炉灶声效与粒子采用配置的间隔、概率和音效参数，在所属线程交付。

以下入口已接入实际机制。相同后缀也在 `farmersdelight` 命名空间提供；配置参数会验证并适配到本插件的行为：

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
papersdelight:remove_effect
papersdelight:upgrade_effect
papersdelight:chorus_teleport
papersdelight:enderman_gristle_teleport
papersdelight:is_sneaking
papersdelight:integer_comparator
```

`dumplings_delight:garlic_effect` 是尚未支持的附属效果，会明确报告。其他未知入口同样不能用空行为伪装成功。上述新配置适配不迁移其他方块实体、库存或旧流体 NBT。

## 作物与耕地随机更新

单层、双层和攀绳作物、沃土、沃土耕地及堆肥会向指定 CraftEngine 快照声明所需的原生随机更新。行为参数 `random-ticking: false`（兼容 `random_ticking`）可关闭该行为的随机更新。单层作物成熟后停止多余更新；双层作物下半部和攀绳作物成熟后仍需更新，以驱动上方生长。双层作物的上半部是否独立生长由 `upper_independent` 控制。

通用耕地的 `water_range`（兼容 `water-range`）默认 4，允许 0–16。检测附近水源时只读取已加载区块，在 Folia 上由各区块所属线程完成取样，再回到耕地所属线程核对当前状态与配置版本。未加载或读取失败的区域记为未知，不据此将湿耕地判为干燥，也不会为检测水源强制加载区块。不同区域的水源观察不是原子快照；变化会在后续随机更新中再次核对。

## 累计统计与占位符

`stats.enabled`（兼容输入 `stats.enable`）默认开启，`stats.flush_interval_seconds`（兼容 `stats.flush_interval`）默认 30 秒。SQLite 工作在专用后台线程，玩家上线预热缓存；读取占位符不会查数据库。停止接受新提交后，落盘与其他关服存档共用 `performance.shutdown_wait_millis` 的期限，默认 5000 ms；旧配置的 `stats.shutdown_wait_millis` 可作为回退。

| 示例 | 查询 |
| --- | --- |
| `%farmersdelight_stats_skewer%` | 当前玩家肉串加工总量 |
| `%farmersdelight_stats_eat__farmersdelight:tomato%` | 当前玩家指定食物食用次数 |
| `%farmersdelight_stats_server__filling%` | 全服灌装总量 |
| `%farmersdelight_stats_player__ydxc2009__cutting_board%` | 已预热玩家砧板总量 |

未知、尚未预热或已关闭的查询返回 `-`。加工在物品与流体成功提交后计数；失败、取消和输出已满不计。厨锅制作使用 `cooking`，玩家取出使用 `cooking_pot`，两者为不同活动，不应相加后当作制作次数。

`stats.cache_player_limit` 默认 2048，后台只淘汰已落盘的离线缓存；在线玩家及待写、待重试的记录保留。预热队列最多同时接受 256 个玩家，未就绪时查询仍返回 `-`；淘汰不删除数据库历史。

## 容器、显示与热源

`container.tick_interval_ticks` 控制重处理间隔（默认 4，范围 1–20 tick），进度按实际经过 tick 结算。FluidCore 漏斗默认独立使用 8 tick 单件节流。显示位置与覆盖、砧板时运、工具音效、煎锅音调范围、炉灶烟粒子概率及手持火焰参数可以配置；粒子密度和连接预算仍共同限制实际交付。

`performance.connection_particle_packet_budget` 默认 64，限制每连接每 200 ms 的粒子包准入和发送。包构造前先检查受众与准入，发送前再次检查连接和预算；该预算只用于本插件粒子，不能表示整台服务器的发包量。

## 内容优先级与编辑

定义按类别和完整 ID 选取：外部包优先、自带补缺；多个外部包重复定义报错。模型/纹理按资源路径选取，语言 JSON 按键合并，双方来源及获胜结果写入受管理资源层的报告。原始内容目录保持只读，配方编辑是明确的例外。

编辑保存定位原文件、根节点和完整 ID，只更新受管理字段，保留其他嵌套扩展数据。未知匹配或消耗条件会禁用该条配方并报告文件、ID 和字段。重载准备、文件读取与文档解析放到后台；物品转换及世界变动按线程归属执行。

## 其他配方类型与行为边界

兼容输入还可能包含 `single`、`decomposition` 类型。`decomposition` 可以带 `ingredient`、`result` 和 `catalysts` 字段，但方块分解的世界条件需要对应游戏机制；当前未实现这些类型，不能仅靠读取字段执行加工。

对于本插件尚未实现或尚未验证的类型，应保留源文件、输出带配方 ID 和来源的诊断，并继续加载可用配方。不会将其退化成厨锅或砧板加工。

本轮 `info` 已按信息卡展示，`single` 尚未适配。兼容声明仅覆盖已经实现并通过验证的条目。

## 已完成验证（1.1.0）

1.1.0 通过 493 项自动测试和仓库语言、资源、配置及 API 边界检查。使用 CraftEngine 26.10 快照与 FluidCore，在 Paper 26.3 和 Folia 26.2 各通过 52 项服内原生检查，覆盖实际事件分发与权限拒绝、流体事务、失败回滚、延迟取消、编辑保存及储罐掉落后重新放置恢复内容。服内检查使用原生玩家与背包测试对象，没有连接游戏客户端；完整内容包的客户端模型及其他未适配玩法仍应单独验收。
