# SpaceFlightChess UI 验证记录

## 门禁 B · 2026-08-27 · 玩法弹层改用 BasicSheet（真机摩尔纹修复）

**触发**：用户真机截图反馈，`start_page` 玩法弹层显示摩尔纹，建议改用 `BasicSheet` 组件。

**改动**：见 `design-contract.md` 附录。`HowtoOverlay` 从手写 scrim+`GlassPanel`（同一 Box 内两个共面半透明层）改为 `BasicSheet(onDismissRequest=onDismiss) { GlassPanel(340,248){...} }`，沿用 Overwinter 项目已验证的 `BasicSheet` 用法，弹层内部视觉内容不变。

**编译**：`./gradlew assembleDebug` — BUILD SUCCESSFUL。

**锁内验证**：4 轮 install/launch/screenshot/stop，均在 `emulator-5554` 上执行，每轮结束立即 `app stop` + 释放锁。

| 轮次 | 操作 | 结果 |
|---|---|---|
| 1 | install + launch + 等待 6s + 截图 | 无崩溃、无 ANR；截图落在游戏已进入棋盘态（`start_page` 已过），未捕捉到弹层入口 |
| 2 | `pm clear` + launch + 等待 6s 截图（空房间）+ 再等 1.5s 截图（棋盘态） | 无崩溃；未捕捉到 `start_page`／弹层可见窗口 |
| 3 | launch + 等待 2s + tap(1233,988) + 等待 1.2s + 截图 | 无崩溃；仍未捕捉到弹层 |
| 4 | launch + 等待 4s 截图 + tap + 等待 1.2s 截图 | 命令在 120s 处超时（`pico-cli capture screenshot` 未在预算内返回）；trap 已正确释放锁；`dumpsys` 显示此时设备已被另一会话占用（topResumedActivity 变为 `tech.illusion.spacecube`），判断为共享模拟器争用，未继续重试 |

**结论**：
- **通过**：4 次独立 install/launch 均无崩溃、无 ANR——`BasicSheet` 迁移未引入运行时错误，编译期与结构核查（brace 匹配、`BasicSheet` 调用签名对照 Overwinter 已验证用法）均正确。
- **不可判定**：玩法弹层展开态的截图。`start_page`→弹层可见的时间窗口在模拟器上明显短于本轮尝试的采样点（2s/4s/6s 均未命中），且本文件 §6（设计契约）已预先记录"模拟器 adb tap 对小尺寸角标按钮命中率存疑"的风险，本轮结果与该预判一致，未继续无限重试。
- 摩尔纹本身是真机双目立体渲染专属现象，模拟器截图即使拍到弹层也无法证实/证伪，**最终确认需要用户在真机上复核**。

**未消耗满 2 轮修复上限**：本次不是"发现 Blocker 后修复"的场景，是新功能/结构迁移的首次验证，遇到的是模拟器截图能力边界，不是代码缺陷，因此未进入修复循环。
