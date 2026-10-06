# 内置村民功能

1.2.3 将村民功能统一到 Farmersdelight-Plugin-Pro 内部，不需要另装 VillagersDelight。
原项目作者与授权说明见 [NOTICE](NOTICE.md)。自定义食物只补充食物点数，繁殖仍服从原版条件。

| 配置 | 功能 |
| --- | --- |
| `villager.enable` | 内置自动化、新增配置交易和背包的总开关 |
| `villager.harvest.enable` | 自定义作物收获 |
| `villager.harvest.replant` | 使用背包中的种子补种 |
| `villager.pickup.enable` | 拾取自定义种子与产物 |
| `villager.breed.enable` | 食物点数喂食 |
| `villager.breed.share-food` | 分享食物，独立于喂食开关 |
| `villager.compost.enabled` | 用剩余自定义产物堆肥 |
| `villager.bonemeal.enabled` | 使用背包中的原版骨粉催熟 |
| `villager.backpack.enabled` | 村民背包入口 |
| `villager.backpack.editable` | 允许有编辑权限的管理员修改背包 |
| `villager.backpack.open-on-sneak` | 潜行右键打开背包 |
| `villager.farmers-buy-crops.enable` | 新增配置中的农民收购条目 |
| `villager.wandering-trader-sells.enable` | 新增配置中的游商销售条目 |

修改后使用 `/fd reload`。配置版本仍是 4，新增普通选项会补齐；已有注册表和自定义值保留。
`world-data.yml` 的原有村民与游商交易池独立配置，不受这些新增交易开关影响。
拾取与喂食分别使用自己的 `scan-interval-ticks`，范围为 5～1200 ticks；额外唤醒不会绕过间隔。
`crops: {}` 可移除显式普通作物清单；管理作物继续服从其自身设置和 `disabled-crops`。
喂食默认点数为卷心菜、番茄、洋葱各 1，稻米 2，稻穗 0。`food-points` 是覆盖表，空表保留内置点数；设为 0 可使某种产物只拾取不食用。

## 耕作与物品规则

`villager.harvest.crops` 声明种子和种植条件，收获模式可选择 `break`、`pick`、`tall` 或 `reset`。
番茄使用采摘事件和初始株；稻米只收获成熟上半，下半保留。
默认识别原装小麦、甜菜、胡萝卜、马铃薯、卷心菜、洋葱、番茄、稻米，自动接入本插件管理的单层、双层和攀绳作物。
新增第三方作物须加入清单；`extra-soils` 追加土壤，`disabled-crops` 按 ID 禁用。流体种植支持原版静态水，不猜测其它流体。
追加土壤仍须符合原作物的存活条件；它不会修改其它插件的原生作物规则。

农民的种子留存按全部槽位合计，默认 32；食用、分享和堆肥保留这份储备。其他职业没有农民种植留存。
堆肥默认为每种产物保留 32 个，每轮最多处理 20 件。背包已满时不提取骨粉。
原版食物由 Minecraft 处理；`discover-foods` 默认关闭，可按需识别更多 CE 食物。

收获、种植、催熟和堆肥尊重 `mobGriefing`、区域归属、区块加载及实体改块取消事件，默认只在白天安排农民工作。
`block-budget` 限制每次查找作物的处理量。每位村民独立调度，空闲降低频率；掉落物、加载与菜单操作唤醒相关村民。

## 村民背包

看向附近村民使用 `/fd villager inventory`，或潜行右键。默认仅管理员拥有以下权限：

- `farmersdelight.villager.inventory`：查看。
- `farmersdelight.villager.inventory.edit`：编辑。

同区域支持左右键、Shift、数字键和副手交换。拖拽、丢弃、双击收集、创造复制会被拒绝。
跨 Folia 区域只读；编辑期间暂停该村民的内置自动化，内容变化时刷新相关槽位并拒绝过期点击。
退出、死亡、卸载、重载和停用不会把副本回写到村民，玩家游标上的真实物品也不会被复制或擦除。

## 可选第三方作物

`villager.harvest.custom-crops` 默认关闭。安装 CustomCrops 后可启用，接入 BLOCK 模式及可解析的 CE／原版种子和阶段。
未知种子提供器、混合阶段或其它模式会明确报告；不会当成成熟作物或消耗不匹配种子。
CustomCrops 骨粉规则由该插件负责，这个桥不绕过其专属施肥规则。

独立 VillagersDelight 仍启用时，`coexistence.pause-with-external-plugin: true` 默认暂停内置自动化。
完成替换后卸下独立插件，只保留一个村民行为执行者；此开关不会停止其他插件。
