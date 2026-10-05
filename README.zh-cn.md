<h1 align="center">
  <img src=".github/assets/farmersdelight-banner.svg" width="640" alt="Farmer's Delight Plugin Pro"><br>
  Farmersdelight-Plugin-Pro
</h1>

<p align="center">在 Paper 和 Folia 上种植、烹饪，分享农夫乐事的美味。</p>

<p align="center">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-blue" alt="AGPL-3.0 授权">
  <img src="https://img.shields.io/badge/All_features-Free-3fb950" alt="全部功能免费">
  <img src="https://img.shields.io/badge/Paper-1.21--26.3-5865F2" alt="Paper 1.21–26.3">
  <img src="https://img.shields.io/badge/Folia-supported-8b5cf6" alt="支持 Folia">
  <img src="https://img.shields.io/badge/Java-21%20%2F%2025-orange" alt="Java 21 / 25 服务端环境">
</p>

<p align="center">
  <a href="https://github.com/Nodemc-developing/Farmersdelight-Plugin-Pro/releases/latest">下载</a> ·
  <a href="#-文档">文档</a> ·
  <a href="README.md">English</a>
</p>

## 🌱 关于

本项目是**灰度 HuiDu_OwO（IOVEYOUMC0）**的 [Farmersdelight-Plugin](https://github.com/IOVEYOUMC0/Farmersdelight-Plugin) 的**独立维护硬分支**，由 **ydxc2009** 持续维护。

**永久免费，永久开源。** 全部功能、发布构建与后续更新均免费。CraftEngine 提供物品、方块与资源包，本插件负责玩法、菜单和自动化。

## ✨ 功能

| 功能 | 包含内容 | 费用 |
| --- | --- | --- |
| 🍲 厨锅 | 热源、烹饪进度、容器处理、漏斗自动化与自定义厨锅组 | 免费 |
| 🔪 砧板 | 工具要求、多项产出、产出概率、音效与自动化 | 免费 |
| 🍳 炉灶与手持烹饪 | 放置烹饪、煎锅翻面、肉串与蔬菜串、可配置粒子 | 免费 |
| 🌾 种植 | 单层、双层、攀绳作物，野生植物、沃土、堆肥与蘑菇群落 | 免费 |
| 🧺 储物与方块 | 篮子、自动收集、红石锁定、绳索、成对方块、比较器与温度伤害 | 免费 |
| 🍽️ 食物与效果 | 营养、舒适、满饥饿进食、效果清除与升级、可配置传送 | 免费 |
| 🛠️ 配方编辑器 | 游戏内编辑、厨具选择、搜索、翻页与逐级返回 | 免费 |
| 🥕 灵活配方 | 等效食材、嵌套分组、标签、组件、调味品、配比与优先级 | 免费 |
| 📦 内容包 | CraftEngine 配方加载、外部内容优先、分类合并与来源文件保存 | 免费 |
| 🫙 流体 | 普通罐与玻璃罐、染色、灌装、排空、浸泡、漏斗加工与内容保留 | 免费 |
| 🏡 村民 | 自定义作物收获补种、食物共享、繁殖条件与可配置交易 | 免费 |
| 📊 统计 | 玩家与物品累计统计、异步 SQLite、PlaceholderAPI 查询 | 免费 |
| 📖 配方发现 | 配方书、关联配方与可选森罗物语自动投料 | 免费 |
| 🏆 成就 | UltimateAdvancementAPI 接入、自动布局与增量同步 | 免费 |

同时提供 Folia 调度、方块睡眠唤醒、异步文件与数据库处理，以及附属 API。

## 🧩 环境与依赖

**1.2.2** 使用原生配置与配方格式。旧工作站配方格式不再加载，升级时请使用新模板。见 [发布说明](RELEASE-NOTES-1.2.2.zh-CN.md)与[配方指南](CONFIGURATION-RECIPES.zh-CN.md)。

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| Paper 或 Folia | **Minecraft 1.21–26.3** | [版本覆盖及限制](docs/COMPATIBILITY.md)；Folia 需有对应的官方核心构建 |
| Java | **1.21.x 用 21；26.x 用 25** | 服务端运行环境；构建使用 JDK 25 |
| [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) | **26.9.2 / 已验证的 26.10 快照** | 必选；[具体构建](docs/DEVELOPMENT.zh-cn.md#支持环境) |
| [FluidCore](https://github.com/Nodemc-developing/FluidCore) | `libs/` 中的兼容版 0.1.0-SNAPSHOT | 流体配方与储罐；旧服必须使用随附的 Java 21 构建 |
| [UltimateAdvancementAPI](https://github.com/Nodemc-developing/UltimateAdvancementAPI) | `libs/` 中的 2.8.1-pro.3 | 覆盖支持版本范围的成就功能 |
| [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) | 可选 | 统计占位符 |

## 🚀 安装

1. 下载[服务端整合包或插件 JAR](https://github.com/Nodemc-developing/Farmersdelight-Plugin-Pro/releases/latest)。
2. 安装受支持的 CraftEngine 构建，将本插件及需要的可选依赖放入 `plugins/`。
3. 启动服务器并接受资源包。自带内容补缺，外部内容包优先。

配方从已启用的 CraftEngine 内容包加载。**不会转换旧流体库的世界或物品数据**；升级已有服务器前，请阅读[配置与配方指南](CONFIGURATION-RECIPES.zh-CN.md)。

## 🎮 命令

| 命令 | 用途 |
| --- | --- |
| `/fd help` | 查看可用命令 |
| `/fd recipe` | 浏览配方 |
| `/fd recipe book` | 打开配方书 |
| `/fd recipe edit` | 选择厨具并编辑配方 |
| `/fd reload` | 重载插件配置与配方 |
| `/fd stats` | 查看运行时统计 |
| `/ce reload` | 重载 CraftEngine 内容与资源 |

编辑、重载和运行时统计需要管理员权限。

## 📚 文档

- [配置与配方](CONFIGURATION-RECIPES.zh-CN.md) · [流体罐操作](FLUID-TANK.zh-CN.md)
- [森罗物语联动与模糊配方](KALEIDOSCOPE-COMPAT.zh-CN.md)
- [依赖与对应源码](libs/README.md) · [构建与技术说明](docs/DEVELOPMENT.zh-cn.md)
- [1.2.2 更新与验证范围](RELEASE-NOTES-1.2.2.zh-CN.md) · [原项目 Wiki](https://github.com/IOVEYOUMC0/FarmersdelightPluginWiKi)

## 🙏 致谢与授权

原插件作者：**灰度 HuiDu_OwO（IOVEYOUMC0）**；本分支维护：**ydxc2009**。感谢 **vectorwing** 与 [Farmer's Delight](https://github.com/vectorwing/FarmersDelight) 原模组贡献者提供原版玩法及 MIT 授权素材。

本项目使用 **AGPL-3.0-only**。使用、修改及再分发（包括商业分发）须遵守 [LICENSE](LICENSE)。第三方素材与依赖保留各自归属和许可，见 [NOTICE](NOTICE.md) · [THIRD_PARTY](THIRD_PARTY.md)。
