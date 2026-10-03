# Farmersdelight-Plugin-Pro

[English](README.md) | **中文**

基于 CraftEngine 的 Farmer's Delight Paper/Folia 插件，提供种植、食材处理、烹饪和游戏内配方管理。

本项目是 [Farmersdelight-Plugin](https://github.com/IOVEYOUMC0/Farmersdelight-Plugin) 的持续维护优化分支。原项目作者：HuiDu_OwO（IOVEYOUMC0）；本分支维护与优化：ydxc2009。

**永久免费、永久开源。** 全部功能、发布构建和后续更新均免费提供。第三方依赖保留各自的授权和发布规则。

## 功能

| 功能 | 内容 | 费用 |
| --- | --- | --- |
| 厨锅 | 烹饪进度、热源、容器处理与自定义厨锅组 | 免费 |
| 砧板 | 工具要求、多项产出、产出概率与音效 | 免费 |
| 炉灶与煎锅 | 放置及手持烹饪、效果与可配置粒子 | 免费 |
| 作物与耕地 | 作物、野生植物、沃土与可配置生长 | 免费 |
| 方块与物品 | CraftEngine 模型、资源包、掉落、绳索、蘑菇群落与储物方块 | 免费 |
| 食物效果 | 营养、舒适与可配置食物效果 | 免费 |
| 配方编辑器 | `/fd recipe edit`，厨具选择、搜索、翻页与逐级返回 | 免费 |
| 模糊配方 | 等效食材、食材分组、理想配比、调味品与优先级 | 免费 |
| 高级食材组 | 嵌套分组、标签引用与多选一原料 | 免费 |
| 内容包配方 | 加载已启用的 CE 内容包，编辑保存回配方来源节点 | 免费 |
| 流体配方 | FluidCore 灌装、排空、浸泡；物品、标签、多选与组件条件 | 免费 |
| 流体罐 | 普通罐与玻璃罐、容量提示、菜单、漏斗加工、染色与保留内容的携带和掉落 | 免费 |
| 手持烹饪 | 煎锅跳跃落地翻面、肉串与蔬菜串烹饪 | 免费 |
| 村民 | 自定义作物收获补种、种子与食物拾取、食物共享、原版繁殖条件与可配置交易 | 免费 |
| 累计统计 | 异步 SQLite、玩家与物品明细、只读缓存的 PlaceholderAPI 查询 | 免费 |
| 通用作物 | 单层、双层及攀绳作物、耕地、沃土、堆肥与野稻 | 免费 |
| 通用方块行为 | 篮子配置、绳索收放与敲钟、成对及双格方块、比较器与温度伤害 | 免费 |
| 配置函数 | 满饥饿进食、清除与升级效果、成就函数、安全传送 | 免费 |
| 表现配置 | 显示位置与覆盖、音效、粒子概率、热源托盘、密度节流与容器间隔 | 免费 |
| 配方发现 | 配方书、信息卡与配方关联跳转 | 免费 |
| 森罗物语联动 | 可选菜谱自动投料至厨锅 | 免费 |
| 成就系统 | 可选 UltimateAdvancementAPI 接入与变化节点同步 | 免费 |
| 调度与性能 | Folia 所属线程访问、异步文件处理、配置缓存与厨锅睡眠唤醒 | 免费 |
| 附属 API | `com.huidu.farmersdelight.api` 与可选玩法附属 | 免费 |

## 安装

- 本版测试目标为 **Paper 26.3** 和 **Folia 26.2**；验收结果以对应构建的本地报告为准。
- 插件使用 Java 21；Minecraft 26.3 服务端需要 **Java 25**。
- 固定使用 **CraftEngine 26.10-SNAPSHOT，构建 26.10-20260929.192451-4**。本版按该快照接口实现，启动时核对原始 JAR 哈希；其他构建需要重新适配、验证。

先安装 CraftEngine，再将 Farmersdelight-Plugin-Pro JAR 放入 `plugins/` 并启动服务器。修改插件配置后使用 `/fd reload`，修改 CraftEngine 资源后使用 `/ce reload`。

流体玩法需要单独安装 **FluidCore**，其 API 不打入本插件 JAR。未安装时，其余玩法仍可使用。兼容新加载的配置、配方和内容包；**不转换旧流体库的世界或物品数据**。已识别的旧载荷会受到保护，不作为空罐覆盖。详见[配置与配方指南](CONFIGURATION-RECIPES.zh-CN.md)。

外部内容包优先，自带内容只补缺。外部包之间同类别、同完整 ID 的冲突会明确报错；模型和纹理按路径选择，语言按键合并。资源生成使用受管理覆盖层，不改写外部素材目录。

标准浏览分类合并为一个入口，包含工具、食材、农作物、加工食品、食物、盛宴、装饰、野生植物和宠物食物九类。已加载物品去重并集，普通罐与玻璃罐分别提供入口。设置 `craftengine-resources.unified-categories: false` 可保留原分类树；该处理仅修改加载副本，不改写原内容包文件。

成就功能需要单独安装 **UltimateAdvancementAPI**。`libs/` 附有 **2.8.1-pro.2** 优化版 JAR、对应源码归档和构建说明，包含 26.2/26.3 适配，已验证 Paper 26.3 和 Folia 26.2；较旧服务端应使用适配对应版本的 API。详见[依赖说明](libs/README.md)。资源包重载后重发成就定义，平时只同步变化的节点。

bStats 3.2.1 的统计 ID 为 **34448**，登记名为 **FarmersDelightPro**。在 `plugins/bStats/config.yml` 设置 `enabled: false` 并重启，可全局关闭统计。统计使用异步发送，支持 Folia，并在插件停用时结束任务。

## 文档

- [配置、配方与迁移](CONFIGURATION-RECIPES.zh-CN.md)
- [流体罐获取与操作](FLUID-TANK.zh-CN.md)
- [森罗物语联动与模糊配方](KALEIDOSCOPE-COMPAT.zh-CN.md)
- [依赖构建与对应源码](libs/README.md)
- [1.2.0 更新与验证范围](RELEASE-NOTES-1.2.0.zh-CN.md)
- [玩家、服主与附属文档](https://github.com/IOVEYOUMC0/FarmersdelightPluginWiKi)

## 构建

使用 **JDK 25** 构建；FD 本体输出 Java 21 字节码。当前 FluidCore 的 26.3 平台模块及测试服运行使用 Java 25。

```text
./gradlew build
```

使用 `-PceJar=<固定快照JAR>`、`-PceLibraries=<CraftEngine/libs目录>` 和 `-PfluidCoreJar=<FluidCore构建JAR>` 指定本地依赖。`ceLibraries` 提供固定快照的重映射运行库；CE Maven 依赖仅限 26.10 快照，运行时仍须符合固定构建。

厨锅及流体罐使用该快照的原生睡眠与唤醒 ticker。附属通过生成的 API-only JAR 编译，并要求与本仓库处于同级目录。性能报告记录实际环境和可比较范围，不将未测试场景描述为性能优势。

## 致谢

感谢 **vectorwing** 与 [Farmer's Delight](https://github.com/vectorwing/FarmersDelight) 原模组贡献者，提供原版玩法及 MIT 授权素材，包括装箱金苹果、装箱金胡萝卜的模型和纹理。原插件开发：**HuiDu_OwO（IOVEYOUMC0）**；本分支维护：**ydxc2009**。

随附第三方素材保留真实来源和许可声明。外部内容包由服主提供，其中新增素材的公开分发需另行确认授权。

## 授权

GNU Affero General Public License v3.0 only，全文见 [LICENSE](LICENSE)。

配方编辑器、配方关联跳转、手持煎锅等全部源码都在本仓库。允许使用、修改及再分发，也允许收费分发；须遵守 AGPL-3.0，保留版权与许可声明、提供完整对应源码、修改版同样以 AGPL-3.0 发布。如果修改版通过网络提供服务，还须向与该服务交互的用户提供源码。

第三方内容和依赖库保留各自的来源及许可，见 [NOTICE.md](NOTICE.md)、[THIRD_PARTY.md](THIRD_PARTY.md) 与 JAR 内随附声明。上游项目及其他分支由各自作者维护。
