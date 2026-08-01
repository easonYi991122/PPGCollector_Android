# 排除项与边界

## 明确排除的材料

- `MigrationPlanning/references/swift/`：不读取、不复制、不作为 Android 设计依据。
- 隔壁工程 `/Users/enene/Data/637/CarotidPlaqueDetectionAPP/PulseApp`：不读取、不复制、不作为 Android 设计依据。
- `MigrationPlanning/docs/` 中除 `MIGRATION_AND_DEVELOPMENT_PLAN.md` 外的文档：不读取、不复制。允许的计划文档也只用于核对本项目现状、阶段和验收原则，不把其中面向 iOS 的建议直接移植为 Android 结论。
- 项目中缓存、临时产物、`__pycache__`、`.pyc`、派生数据及无法追溯来源的文档。

## Python 快照中未归档的代码

下列部分不进入 `reference_sources/python_gui/`，避免 Android 开发误用旧设备栈：

- 旧 BLE/NUS 与传输：`ble_nus.py`、`device_profile.py`、`transport.py` 及相应测试；
- 旧协议实现：`ppg_monitor/protocol/` 及相应测试；
- 旧 CSV 兼容：`legacy_csv.py` 及相应测试；
- IMU：`imu.py`、`imu_cardiac.py`、`imu_quality.py` 及相应测试。当前 CUP PPG 交付没有确认 IMU 数据契约，列为未来扩展；
- 与上述排除模块强耦合、且对 Android V1 无新增证据的文件。

这些排除不代表功能永远取消；若后续得到固件契约、产品需求和金标，可通过独立 ADR 纳入新版本。

## 不在本轮范围内

- 直接创建 Android Studio 工程、签名、上架或商店素材；本轮交付是可执行的详细规划和参考源快照。
- 修改当前 iOS PPGCollector 的产品代码。
- 猜测 SpO2、血压或临床判定算法。缺少已验证模型/校准参数时必须保持不可用。
- 用 Python/C++ 运行时嵌入代替 Kotlin 移植。Python/C++ 文件只用于交叉核验；如未来决定采用 NDK，需单独做性能、ABI、崩溃隔离和许可证评审。
