# 来源与授权

本仓库（Farmersdelight-Plugin-Pro）采用 **AGPL-3.0**（GNU Affero General Public License version 3）授权，
全文见 [LICENSE](LICENSE)。

允许使用、修改、再分发，也允许收费分发；但再分发时必须满足 AGPL-3.0 的条件：保留版权与授权声明、
附带完整对应源码、修改过的版本同样以 AGPL-3.0 授权。以 jar 形式散发的修改版也必须提供源码。
AGPL 第 13 条还要求：**如果你修改后的版本通过网络对外提供服务，也要向使用者提供源码。**

本插件是**完整开源**的：配方编辑器、配方关联跳转、手持煎锅烹饪等全部功能都在本仓库的这一份
源码里。

## 第三方内容

插件自身的代码由本仓库以 AGPL-3.0 发布。它同时搬运了同样以自由许可证发布的第三方内容，这些内容的
版权仍归原作者，并按各自的许可证分发：

| 内容 | 来源 | 作者 | 许可证 |
|---|---|---|---|
| 贴图、配方、数值、游戏行为 | Farmer's Delight（Minecraft 模组） | vectorwing | MIT |
| AntiGriefLib（shade 进 jar，重定位到 `com.huidu.farmersdelight.libs`） | AntiGriefLib | XiaoMoMi | MIT |
| bStats（shade 进 jar，重定位到 `com.huidu.farmersdelight.libs`） | bStats | Bastian Oppermann | MIT |
| 村民食物点数与种子留存规则的整合 | VillagersDelight，固定提交 `26148f212bf893af232320b0deacbbfb5a2e3fb4` | HuiDu_OwO；整合修改 ydxc2009 | AGPL-3.0-only |

MIT 与 AGPL-3.0 兼容，第三方内容的 MIT 声明随 jar 分发，完整文本见
`src/main/resources/NOTICE.txt`（也就是打进发布 jar 的那一份）。

## 运行时依赖

流体罐内容包的 9 项罐体模型与贴图直接来自 Farmer's Delight 固定提交
`0b424c370dc48197a9231771d55f0dcc3a36844e`，保留 vectorwing 归属及 MIT 文本。
16 个液位模型定义由本项目工具自行生成。旧客户端另有 32 个合并模型，
组合上述已确源罐体几何与自生成液位；其固定蓝色液体贴图由本项目工具自行生成。
见 [素材说明](src/main/resources/craftengine/farmersdelight_fluids/ASSET-NOTICE.txt)
和 [随附授权](src/main/resources/craftengine/farmersdelight_fluids/LICENSE)。
逐项来源、版本、许可与 SHA-256 见
[素材清单](src/main/resources/craftengine/farmersdelight_fluids/ASSET-ORIGINS.json)。
不将上游 9 项素材主张为本项目原创。

以下依赖不打进 Farmersdelight-Plugin-Pro 的 jar，只在运行时调用：

- CraftEngine（GPL-3.0），内容平台与配方数据来源
- FluidCore（实现模块 GPL-3.0，API 模块 Apache-2.0），可选流体机制，API 不打入本插件 JAR。
- UltimateAdvancementAPI（LGPL-3.0-or-later），成就系统；`libs/` 中的 `2.8.1-pro.3` 基于
  [Nodemc-developing/UltimateAdvancementAPI](https://github.com/Nodemc-developing/UltimateAdvancementAPI)，
  由 fren_gor、EscanorTargaryen 等原作者开发。对应修改源码、构建脚本和许可证随本仓库提供，见
  [libs/README.md](libs/README.md)。

## 对应版本与源码

修改版维护者：ydxc2009。当前项目源码地址为
[Nodemc-developing/Farmersdelight-Plugin-Pro](https://github.com/Nodemc-developing/Farmersdelight-Plugin-Pro)。
发布构建附带对应版本源码归档，仓库标签为 `v1.2.2`；JAR 内保留 AGPL 全文和版本说明。本地审阅构建以随附的对应源码归档为准。

SQLite JDBC 3.53.4.0 随主 JAR 提供，其 Apache-2.0、原驱动及原生依赖声明保留于
`META-INF/maven/org.xerial/sqlite-jdbc/`。其他打包依赖见 [THIRD_PARTY.md](THIRD_PARTY.md)。

村民功能整合使用 [VillagersDelight](https://github.com/IOVEYOUMC0/VillagersDelight/tree/26148f212bf893af232320b0deacbbfb5a2e3fb4)
的公开功能规格与食物留存规则，在本插件的所属线程调度、CE 作物接口和菜单机制内实现。
未打包独立 VillagersDelight JAR 或其旧版 NMS 模块；原作者归属与 AGPL 全文随源码和制品保留。
