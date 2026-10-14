# 1.2.5 本地修复候选

本轮仅提供本地审阅制品，未提交、推送或发布到 GitHub。包含 1.2.4 工作区中的 CE 资源冲突修复。

## 煎锅支架

- 修正热源条目省略 `tray` 时被当成 `false` 的问题。省略该字段时，恢复按热源形状自动判断；显式 `true` / `false` 继续有效。
- 营火等非完整热源上显示支架；完整方块、未点燃营火或纯空气下方不显示，沿用上游的判断规则。
- 热源的物理变化、交互及区块加载处理会刷新支架；同一位置下一 tick 前的多次通知合并处理，方块操作留在所属区域线程。
- 布尔配置拼写错误会报告具体字段，不再静默当作 `false`。

## 手动内容包

1. 停服并备份现有内容包及个人修改，将本次 JAR 放入 `plugins/`，替换旧 FD 制品。
2. 首次安装：把 `Farmersdelight-Plugin-Pro-1.2.5-content-pack.zip` 解压到 `plugins/CraftEngine/resources/`。该目录下应直接出现 `farmersdelight/`、`farmersdelight_fluids/`，不要再套一层 ZIP 文件名目录。
3. 已有服务器可继续使用现有内容包；升级内容时手动比较、合并修改。默认配方在 `farmersdelight/configuration/recipes/`，流体配方在 `farmersdelight_fluids/configuration/`。使用流体内容需安装兼容的 FluidCore。
4. 启动服务器，按 CraftEngine 的正常流程生成资源包并让客户端接受。

启动及重载不再释放、补齐或隐式加载 JAR 内的内容包、资源和默认配方；只加载 CE 已启用的包。旧配置中的 `craftengine-resources.auto-completion` 与 `recipes.merge-missing-bundled` 均不会重新启用补齐。主配置、菜单和插件语言文件仍按原流程初始化。

相同原生方块状态指向同一目标时合并重复映射，解决手动包与 CE 内置铁活板门映射的误冲突；映射结果不同仍报告双方文件并拒绝冲突。普通物品、方块、配方定义的冲突规则不变。

内容 ZIP 附带现有素材归属及许可。本轮未引入新的第三方实现或模型。

## 验证范围

详见 [本轮验证记录](verification/manual-content-and-skillet-2026-10-07/README.md)。自动验证覆盖配置加载、实际服务端支架状态和物品保留；真实客户端的最终模型画面仍需人工查看。本轮未进行性能跑分，也没有据此宣称性能提升倍数。
