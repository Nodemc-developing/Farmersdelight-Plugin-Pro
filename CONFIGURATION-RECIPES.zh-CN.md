# 配置、配方与内容包

内置村民耕作、种子留存、堆肥、催熟和背包开关见 [村民配置说明](VILLAGER.zh-CN.md)。

Farmersdelight-Plugin-Pro 1.2.2 使用独立的 `farmersdelight_recipes` 配方格式。厨锅、砧板和流体加工共用清晰的 `input`、`output`、`process` 分组，由 `station` 指定工作站。格式整理不改变已有食材、产出、加工和自动化规则。

主配置要求 `config-version: 4`，功能字段使用连字符命名，例如 `cooking-pot`、`recipe-book.tag-cycle-interval-ticks`。版本不符时明确报告，不自动迁移既有配置；使用本版本自带模板配置服务器。

## 文件位置

配方仍放在已启用的 CraftEngine 内容包中：

```text
plugins/CraftEngine/resources/farmersdelight/configuration/recipes/
  cooking_pot_recipes.yml
  cutting_board_recipes.yml
  food_groups.yml

plugins/CraftEngine/resources/farmersdelight_fluids/configuration/
  fluid_recipes.yml
```

文件名和子目录用于整理内容；解析由顶层区块决定。其他内容包也可在自己的 `configuration/` 下提供配方。工作台、熔炉等原版合成仍使用 CraftEngine 的 `recipes` 区块；本插件的工作站配方使用 `farmersdelight_recipes`。

## 统一结构

```yaml
farmersdelight_recipes:
  example:recipe_id:
    station: cooking_pot
    input: {}
    output: {}
    process: {}
```

每条配方以唯一 ID 为键，建议使用 `命名空间:路径`。同一文件可以混合不同工作站，根节点支持 `farmersdelight_recipes#分组名`；后缀用于区分区块，不改变工作站类型。配方的 `group` 字段指定自定义厨锅组。

| 字段 | 作用 |
| --- | --- |
| `station` | `cooking_pot`、`cutting_board` 或 `fluid_tank` |
| `input` | 原料、厨锅容器或砧板工具条件 |
| `output` | 成品及数量；砧板使用成品列表 |
| `process` | 时间、经验或声音；只填写该工作站支持的字段 |
| `matching` | 厨锅精准或模糊匹配设置 |
| `fluid`、`operation` | 流体罐的流体条件和加工方向 |
| `priority`、`category`、`group` | 选择优先级、分类及自定义厨锅组；不需要时省略 |

本版本只加载本插件的统一格式。未知工作站、未知匹配条件或不支持的消耗条件会报告文件、配方 ID 和字段；不会将它们当作无条件配方运行。`extensions` 和 `x-` 前缀的数据用于扩展，保存时保留，不代表插件会执行该数据。

## 厨锅

```yaml
farmersdelight_recipes:
  example:cooking/vegetables:
    station: cooking_pot
    input:
      items:
        - minecraft:carrot
        - {items: [minecraft:potato, minecraft:beetroot]}
      container: none
    output:
      item: minecraft:baked_potato
      count: 1
    process:
      ticks: 120
      experience: 0.1
    category: meals
    priority: 0
```

这是结构示例，实际成品和食材由服务器自行设定。

| 字段 | 格式及默认规则 |
| --- | --- |
| `input.items` | 非空食材列表；普通厨锅最多 6 个原料格 |
| `input.container` | 容器物品；`none` 明确不需要容器，省略时沿用成品剩余物自动推断 |
| `output.item` | 成品 ID，必填；成品对象也可带 `count`、`nbt`、`components` |
| `output.count` | 正整数，默认 1；基础产出数量 |
| `process.ticks` | 整数 tick；缺省值及上下限使用厨锅配置，20 tick = 1 秒 |
| `process.experience` | 经验值，默认 0.0 |
| `priority` | 优先级，默认 0 |
| `category` | 分类，默认 `misc` |
| `group` | 自定义厨锅组，省略时属于默认组 |

需要两份相同食材时，在 `input.items` 中列出两项。候选项只是“多选一”，不会增加本轮的食材份数。

### 模糊匹配

```yaml
farmersdelight_recipes:
  example:cooking/meat_stew:
    station: cooking_pot
    input:
      container: minecraft:bowl
    output:
      item: farmersdelight:beef_stew
      count: 1
    process:
      ticks: 200
    matching:
      mode: fuzzy
      perfect:
        minecraft:beef: 2
        farmersdelight:tomato: 1
      use-equivalent-foods: true
      use-seasonings: true
      minimum-score: 0.15
```

`matching.mode` 默认 `exact`。`fuzzy` 使用 `matching.perfect` 中的具体物品及理想份数，份数必须为正整数；食材种类不超过当前厨锅限制。等效食材、调味品和品质仍按现有规则处理，精准配方优先。`use-equivalent-foods` 与 `use-seasonings` 默认开启，`minimum-score` 默认为 0.15，范围为 0–1。

## 砧板

```yaml
farmersdelight_recipes:
  example:cutting/carrot:
    station: cutting_board
    input:
      item: {items: [minecraft:carrot, minecraft:golden_carrot]}
      tools:
        - "#minecraft:axes"
        - minecraft:shears
    output:
      - item: minecraft:orange_dye
        count: 2
      - item: minecraft:wheat_seeds
        chance: 0.25
    process:
      sound:
        id: minecraft:block.wood.break
        volume: 0.8
        pitch: 1.0
```

| 字段 | 格式及默认规则 |
| --- | --- |
| `input.item` | 一个食材表达式；每次处理一件物品 |
| `input.tools` | 非空工具 ID 或标签列表 |
| `output` | 非空成品列表；每项支持 `item`、`count`、`chance`、`nbt`、`components` |
| `output[].count` | 默认 1；小于 1 时沿用现有边界处理 |
| `output[].chance` | 默认 1.0；概率限制在 0–1 |
| `process.sound` | `{id, volume, pitch}`；省略时沿用砧板配置的音效 |

切割的时运、工具损耗和自动化规则保持不变；格式不会额外引入输入数量或工具消耗字段。

## 食材表达式与组件

| 表达式 | 含义 | 示例 |
| --- | --- | --- |
| `minecraft:物品` | 原版物品 | `minecraft:carrot` |
| `命名空间:物品` | 自定义物品 | `example:carrot_slice` |
| `#命名空间:标签` | 物品标签 | `"#minecraft:planks"` |
| `advtag:命名空间:标签` | 独立高级标签 | `advtag:example:vegetables` |
| `{items: [...]}` | 列表中任意一个候选符合即可 | `{items: [minecraft:carrot, minecraft:potato]}` |
| `{item: ..., nbt: ...}` | ID 及编辑器保存的完整物品快照 | 由编辑器保存自定义物品 |
| `"#标签,!物品,!#标签"` | 标签成员排除 | `"#minecraft:planks,!minecraft:oak_planks"` |

`#` 开头的字符串需要引号，避免成为 YAML 注释。候选可以嵌套，重复候选不会增加需求量。`advtag:` 由高级标签解析器处理，与普通 `#` 标签分别注册。

结果物品的 `components` 支持 CE 组件数据；完整 `nbt` 快照用于保留编辑器捕获的物品属性。厨锅、砧板和流体加工的**物品输入**沿用 ID、标签、候选和完整快照匹配，尚未实现的物品输入 `components` 或 `exact-components` 条件会明确拒绝。下文 `fluid.match` 的组件条件作用于**流体变体**，由 FluidCore 执行真实匹配。

高级标签单独声明，原有定义方式不变：

```yaml
advanced_tags:
  example:roots:
    values: [minecraft:carrot, minecraft:potato]
  example:vegetables:
    values: [advtag:example:roots, minecraft:beetroot]
```

所有定义收集后再展开；未定义引用、循环引用或空成员会输出诊断。食材分组使用独立的 `food_groups` 区块，详细规则见[森罗兼容与模糊配方](KALEIDOSCOPE-COMPAT.zh-CN.md)。

## 流体罐：灌装、排空与浸泡

流体加工需要 FluidCore。支持的 CE 版本为 26.9.2 和已验证的 26.10 快照，精确制品与版本限制见[兼容说明](docs/COMPATIBILITY.md)。

```yaml
farmersdelight_recipes:
  example:fluid/wet_sponge:
    station: fluid_tank
    operation: soak
    input:
      item: minecraft:sponge
    output:
      item: minecraft:wet_sponge
    fluid:
      match: "#c:water"
      amount-mb: 1000
      consume: true
    process:
      ticks: 20
```

| 字段 | 作用及默认规则 |
| --- | --- |
| `operation` | `fill` 从罐灌装、`drain` 将容器流体排入罐、`soak` 浸泡 |
| `input.item` | 每次处理一件输入物品；可使用食材表达式 |
| `output` | 成品对象；浸泡必填，灌装或排空可省略并采用真实容器处理器的产物 |
| `fluid.match` | 流体 ID、流体标签或高级流体条件；必填 |
| `fluid.amount-mb` | 正整数 mB，默认 1000；一桶 1000 mB，一瓶 250 mB |
| `fluid.consume` | 默认 `true`；仅浸泡允许 `false`，仍要求罐中有完整指定数量 |
| `process.ticks` | 默认 0；原生储罐输入的灌装、排空即时执行，浸泡使用此时长 |
| `priority` | 默认 0，数值较大的配方优先 |

高级流体条件仍使用 FluidCore 匹配接口，例如：

```yaml
fluid:
  match:
    any-of:
      - id: minecraft:water
        components:
          example:quality: clean
        exact-components: false
      - tag: c:milk
  amount-mb: 250
  consume: true
```

`fluid.match` 中可指定 `id`、`tag` 或 `any-of`，以及 `components` 和 `exact-components`。`exact-components: true` 要求组件集合精确相同，`false` 只核对声明的组件。组件键必须是完整 ID；实际流体及组件必须已由相应扩展注册。所有候选使用同一个转移数量，不允许候选暗中覆盖 `fluid.amount-mb`。

自带包包含 **24 条流体配方**：3 条灌装、2 条排空和 19 条浸泡。已有海绵和其他浸泡配方显式使用 20 tick，格式转换保留这些等待时间。FluidCore 默认注册水、牛奶、岩浆、蜂蜜及对应标签，支持原版桶瓶处理。

罐容量不足、输入不符、数量不足、输出已满、权限被拒绝或物品流体数据受保护时，整次加工取消，物品和流体均不提前扣除。排空读取容器实际流体及组件，再核对 `fluid.match`，不能从标签猜测流体；显式固定成品也不能丢弃组件或容器剩余流体。

等待期间不持有流体事务。完成前重新核对输入、流体、权限和目标状态，再在所属线程提交；不修改既有 Folia 归属、补偿和自动化规则。旧流体库的世界、物品存档不自动转换，已识别的未知载荷保留并保护。

服务器也可补充流体元数据；定义不是向罐中生成流体：

```yaml
"fluidcore:fluids":
  minecraft:honey:
    display-name: "蜂蜜"
    tags: [c:honey]
    color: "#FFE8AA2B"
```

以下为完整的 FluidCore 储罐定义，放在已启用的 CE 内容包 `configuration/` 中即可。它使用原版铁块模型，容量为 16000 mB，掉落表只有一个自身物品条目；先固定数量为 1，再保存储罐数据：

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

本插件同时读取自身的 `cooking_pot_recipes`、`cutting_board_recipes` 和 `custom_cooking_pot_recipes` 区块。菜单操作和森罗菜谱自动投料由 Farmersdelight-Plugin-Pro 处理。

## 主配置

主配置以 `lang`、`heat_sources`、`cooking_pot`、`cutting_board`、`skillet`、`stove`、`pet_food`、`recipe_book` 等下划线分组组织。原有配置迁移后保留本插件独有选项，显式设置应优先于新版本默认值。

`heat_sources` 是规则列表，支持原版 `material`、自定义方块 `ce_block` 和自定义方块标签 `ce_block_tag`，并检查配置的 `states` 条件。`heat_source`、`conductor` 分别控制热源与导热判断；`tray: true` 要求对应托盘，托盘定义由同一热源规则核对。

其他 CraftEngine 内容包可能使用单独注册的方块行为、物品事件函数、进度配置及附属入口。使用前应确认日志中没有未知行为、未知函数或未解析标签。

## 方块行为与配置函数

本插件在加载内容包之前注册以下入口。配置参数会验证并应用到对应机制；遇到已注册的相同 ID 时，不覆盖其行为。

```text
farmersdelight:basket
farmersdelight:advanced_crop
farmersdelight:roped_crop
farmersdelight:double_crop
farmersdelight:grant_advancement
farmersdelight:remove_random_effect
farmersdelight:organic_compost
farmersdelight:rich_soil
farmersdelight:farmland
farmersdelight:rope
farmersdelight:rope_block
farmersdelight:skewer_item
farmersdelight:high_temperature
farmersdelight:pairable_block
farmersdelight:horizontal_double_block_item
farmersdelight:horizontal_double_block
farmersdelight:wild_rice
farmersdelight:remove_effect
farmersdelight:upgrade_effect
farmersdelight:chorus_teleport
farmersdelight:enderman_gristle_teleport
farmersdelight:is_sneaking
farmersdelight:integer_comparator
```

未知入口会明确报告。本页的新配置说明不包含其他方块实体、库存或旧流体 NBT 的转换。

## 作物与耕地随机更新

单层、双层和攀绳作物、沃土、沃土耕地及堆肥会向指定 CraftEngine 快照声明所需的原生随机更新。行为参数 `random-ticking: false`（兼容 `random_ticking`）可关闭该行为的随机更新。单层作物成熟后停止多余更新；双层作物下半部和攀绳作物成熟后仍需更新，以驱动上方生长。双层作物的上半部是否独立生长由 `upper_independent` 控制。

通用耕地的 `water_range`（兼容 `water-range`）默认 4，允许 0–16。检测附近水源时只读取已加载区块，在 Folia 上由各区块所属线程完成取样，再回到耕地所属线程核对当前状态与配置版本。未加载或读取失败的区域记为未知，不据此将湿耕地判为干燥，也不会为检测水源强制加载区块。不同区域的水源观察不是原子快照；变化会在后续随机更新中再次核对。

## 累计统计与占位符

`stats.enabled` 默认开启，`stats.flush-interval-seconds` 默认 30 秒。SQLite 工作在专用后台线程，玩家上线预热缓存；读取占位符不会查数据库。停止接受新提交后，落盘与其他关服存档共用 `performance.shutdown-wait-millis` 的期限，默认 5000 ms。

| 示例 | 查询 |
| --- | --- |
| `%farmersdelight_stats_skewer%` | 当前玩家肉串加工总量 |
| `%farmersdelight_stats_eat__farmersdelight:tomato%` | 当前玩家指定食物食用次数 |
| `%farmersdelight_stats_server__filling%` | 全服灌装总量 |
| `%farmersdelight_stats_player__ydxc2009__cutting_board%` | 已预热玩家砧板总量 |

未知、尚未预热或已关闭的查询返回 `-`。加工在物品与流体成功提交后计数；失败、取消和输出已满不计。厨锅制作使用 `cooking`，玩家取出使用 `cooking_pot`，两者为不同活动，不应相加后当作制作次数。

`stats.cache-player-limit` 默认 2048，后台只淘汰已落盘的离线缓存；在线玩家及待写、待重试的记录保留。预热队列最多同时接受 256 个玩家，未就绪时查询仍返回 `-`；淘汰不删除数据库历史。

## 容器、显示与热源

`container.tick-interval-ticks` 控制重处理间隔（默认 4，范围 1–20 tick），进度按实际经过 tick 结算。FluidCore 漏斗默认独立使用 8 tick 单件节流。显示位置与覆盖、砧板时运、工具音效、煎锅音调范围、炉灶烟粒子概率及手持火焰参数可以配置；粒子密度和连接预算仍共同限制实际交付。

`performance.connection-particle-packet-budget` 默认 64，限制每连接每 200 ms 的粒子包准入和发送。包构造前先检查受众与准入，发送前再次检查连接和预算；该预算只用于本插件粒子，不能表示整台服务器的发包量。

## 内容优先级与编辑

定义按类别和完整 ID 选取：外部包优先、自带补缺；多个外部包重复定义报错。模型/纹理按资源路径选取，语言 JSON 按键合并，双方来源及获胜结果写入受管理资源层的报告。原始内容目录保持只读，配方编辑是明确的例外。

编辑保存定位原文件、根节点和完整 ID，只更新受管理字段，保留其他嵌套扩展数据。未知匹配或消耗条件会禁用该条配方并报告文件、ID 和字段。重载准备、文件读取与文档解析放到后台；物品转换及世界变动按线程归属执行。

## 已完成验证（1.1.0）

1.1.0 通过 493 项自动测试和仓库语言、资源、配置及 API 边界检查。使用 CraftEngine 26.10 快照与 FluidCore，在 Paper 26.3 和 Folia 26.2 各通过 52 项服内原生检查，覆盖实际事件分发与权限拒绝、流体事务、失败回滚、延迟取消、编辑保存及储罐掉落后重新放置恢复内容。服内检查使用原生玩家与背包测试对象，没有连接游戏客户端；完整内容包的客户端模型及其他未适配玩法仍应单独验收。
