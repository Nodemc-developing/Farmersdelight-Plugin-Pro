# 构建与技术说明

[README](../README.zh-cn.md) · [English](DEVELOPMENT.md)

## 支持环境

- 已验证目标：**Paper 26.3**、**Folia 26.2**。具体构建与验收范围见发布说明；尚未验证 Folia 26.3。
- 服务端运行与构建工具链使用 **Java 25**；Farmersdelight-Plugin-Pro 本体输出 Java 21 字节码。
- CraftEngine 固定为 **26.10-SNAPSHOT，构建 `26.10-20260929.192451-4`**。启动时核对原始 JAR 哈希，其他构建需要重新适配、验证。Maven 快照范围不代表支持其中所有运行时构建。
- 流体玩法需单独安装 **FluidCore 0.1.0-SNAPSHOT，Build 2**；成就需单独安装 **UltimateAdvancementAPI 2.8.1-pro.2**，其中包含 26.2/26.3 适配器。两项依赖均不打入本插件或 API JAR。

依赖构建、对应源码、构建步骤与授权见 [libs/README.md](../libs/README.md)。

## 构建

使用 JDK 25。首次检出时，先获取验证测试所需的公开 API 文档：

```text
git clone https://github.com/IOVEYOUMC0/FarmersdelightPluginWiKi.git wiki
```

随后构建：

```text
./gradlew build
```

指定本地依赖：

```text
./gradlew build -PceJar=<固定快照JAR> -PceLibraries=<CraftEngine/libs目录> -PfluidCoreJar=<FluidCore构建JAR>
```

`ceLibraries` 提供固定快照的重映射运行库。不指定本地 JAR 时，CE Maven 依赖仅限 26.10 快照；服务端仍须使用固定构建。

构建产物包括主插件 JAR 和供附属编译的 API-only JAR。API 包为 `com.huidu.farmersdelight.api`，附属以 `compileOnly` 引用 API JAR。现有附属构建方式要求其源码检出至本仓库的同级目录。

## 内容加载

配方从已启用的 CraftEngine 内容包加载。编辑时按原文件、根节点与配方 ID 保存，并保留不受管理的扩展字段。

外部内容包优先，自带内容只补缺。外部包之间同类别、同完整 ID 的冲突会明确报错；模型与纹理按资源路径选择，语言 JSON 按键合并。资源生成使用受管理覆盖层，不改写外部素材目录。

标准浏览分类合并为一个入口，包含工具、食材、农作物、加工食品、食物、盛宴、装饰、野生植物和宠物食物九类。已加载物品去重并集，普通罐与玻璃罐分别提供入口。设置 `craftengine-resources.unified-categories: false` 可保留原分类树；该处理仅修改加载副本，不改写原内容包。

不转换旧流体库的世界或物品数据。已识别的旧载荷受到保护，不会被当作空罐覆盖。详见[配置与配方](../CONFIGURATION-RECIPES.zh-CN.md)及[流体罐说明](../FLUID-TANK.zh-CN.md)。

## 调度与更新

世界、玩家与背包操作留在所属服务端线程，文件解析和数据库操作异步执行。厨锅与流体罐使用固定 CraftEngine 快照的原生睡眠与唤醒 ticker。

成就平时只同步变化的节点，资源包重载后重发定义。适配器限制与实现说明见[依赖文档](../libs/README.md)。

性能报告记录实际测量环境和可比较范围，不将未测试场景描述为性能优势。正确性验收与真实客户端检查范围见[发布说明](../RELEASE-NOTES-1.2.0.zh-CN.md)。

## 使用统计

bStats 3.2.1 的插件 ID 为 **34448**，登记名 **FarmersDelightPro**。在 `plugins/bStats/config.yml` 设置 `enabled: false` 并重启，可全局关闭统计。统计异步发送，支持 Folia，并在插件停用时结束任务。

## 源码与授权

配方编辑器、配方关联与手持煎锅等全部功能源码均在本仓库。AGPL-3.0-only 允许使用、修改与再分发，包括收费分发；须保留版权与许可声明、提供完整对应源码，并以相同许可发布修改版。修改版通过网络提供服务时，也须向与该服务交互的用户提供源码。

Farmer's Delight 素材保留 vectorwing 的 MIT 授权，包括装箱金苹果和装箱金胡萝卜模型、纹理。随附素材保留来源声明；服主自行提供的外部素材需确认相应分发权限。第三方依赖保留各自许可和发布规则。详见 [LICENSE](../LICENSE)、[NOTICE](../NOTICE.md)、[THIRD_PARTY](../THIRD_PARTY.md) 与 JAR 内声明。
