# 森罗茶壶放置兼容回归

装水的森罗茶壶保存 `custom_data["kaleidoscopecookery:teapot_data"].fluid`。原流体放置监听器在所有 CraftEngine 放置事件中递归寻找外来流体数据，因此同时取消了森罗原生茶壶的放置，与点击普通方块还是冷热炉无关。

修复以尝试放置的 `blockState().behavior()` 判断是否为真正 FluidCore tank，包含组合行为和其他命名空间。FluidCore 目标继续保护外来数据；其他目标交回自己的处理器，但附带真正的 FluidCore `container_data`、`container_initialized` 或 `tank_data` 记录时仍拒绝，避免数据丢失。流体配方转换及 FluidCore 解码器的保护规则未改动。版本为 `1.2.3-SNAPSHOT` 候选，正式发布暂停。

[results.json](results.json) 记录制品摘要、每条断言、十八种实际事件案例及原始报告/控制台摘要。完整报告保留于登记测试服的隔离配置档。专用探针源文件在森罗仓库的 `verification/fluid-compat/TeapotPlacementProbe.java`，其说明记录复验条件及玩家模拟边界。

| 验证 | 结果 |
| --- | --- |
| 默认公开 CE 26.9.1 API 的 `test shadowJar` | 841 项，失败/错误/跳过均为 0 |
| 实际 CE 26.9.2 API 的 `test shadowJar` | 841 项通过，同一生产制品 |
| 实际 CE 26.10 API 编译 | 通过 |
| Paper 26.3 build 140，旧 FD 1.2.2 + CE 26.10 | 131 项故障复现检查通过，十八种茶壶事件全部被错误取消 |
| 同一核心，FD 修复候选 + CE 26.10 | 514 项通过，十八种事件放行且数据往返保持一致 |
| 同一核心，FD 修复候选 + CE 26.9.2 | 514 项通过，同一修复制品 |

FluidCore 为最新本地源码重构的 `0.1.0-SNAPSHOT` 制品（与 Build 3 发布制品字节相同），摘要 `ae4c584cf18c1c11d716fed395bb8cdcdf05a204d1fcb68b784128220933f04a`；森罗为已合入 dev 的 `1.3.0-SNAPSHOT`，摘要 `673450ce636f205dd39cbe6b495175d5b5edf9dc45338128c5569322dd08a2c7`；FD 修复制品摘要 `bbd954e8da8aec965525e8276624357d8bec80d8c6d775a7973dd5f8c7be5b01`。

三种茶壶为水、岩浆、保存完整结果物品 NBT 且剩余三份的成茶；分别覆盖普通方块、未点燃及点燃的森罗炉，主副手各一次。每项在真实 CE 方块与森罗控制器中恢复、整壶拾取两次。另验证实际原生储罐拒绝外来数据、错误 PDC 类型不被转换或覆盖、输入/输出/储罐内容保持不变。五个新增单元测试覆盖行为归属、组合行为、既有取消、可选依赖缺失及混合原生记录。

使用模拟玩家分发真实事件及驱动真实控制器；未覆盖完整 NMS 玩家点击与扣物品、客户端画面。本次新回归在 Paper 上执行，Folia 线程边界检查限于代码审查。复用登记核心与隔离配置档，清理临时方块和 global 区块票据后才写成功报告；原世界、基础资源配置和其他任务不受修改。
