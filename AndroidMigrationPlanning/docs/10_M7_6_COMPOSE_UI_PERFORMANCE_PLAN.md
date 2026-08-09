# M7.6 单轮 Compose UI、状态与性能收敛规划

版本：1.0
日期：2026-08-09
执行轮次：`M7.6`（单轮完成）

## 1. 文档地位与范围

本文是用户 2026-08-09 提出的“已保存会话顶部修正、双视图 UI、录制态信息密度、Compose 最佳实践与性能收敛”的本轮权威执行文档。开发必须先以本文冻结的页面层级、状态所有权和验收门禁为准，再修改源代码。

既有 [`09_M7_CAPTURE_TRACEABILITY_SUBJECT_ARCHIVE_PLAN.md`](09_M7_CAPTURE_TRACEABILITY_SUBJECT_ARCHIVE_PLAN.md) 继续保留为 M7.0～M7.5 的数据合同、五轮开发事实和历史需求依据，但其中“第 5 轮为最终 UI 收尾”的时间性描述不再约束 M7.6；本轮 UI/Compose 行为冲突时以本文为准。`docs/07`、`docs/08` 继续仅作为 M6 滤波历史基线和 2026-08-02 审计快照，不得用它们回退当前 0.5～12 Hz、session v2、subject archive 或双视图合同。

本轮不修改 `reference_sources/`，不改变 `CUPRAW1`、25 列原始 CSV、metrics/BP sidecar schema、1 Hz 指标时间轴、滤波频带、BP/SpO₂ 有效性或 FGS/writer 所有权。

## 2. 需求 ID 与验收结果

| ID | 目标 | 可验收结果 |
|---|---|---|
| `M7-UI-002` | 已保存会话统一页面骨架 | 被试档案与逐文件视图共享固定顶栏；返回始终退出到录制页，切换视图只改变内容；窄屏/大字体不再把标题和四个按钮塞在同一行 |
| `M7-UI-003` | 上下文选择与安全操作 | 非选择态只显示刷新、切换视图、选择；选择态显示导出、删除、全选、取消；选择数按去重后的实际会话数；删除有二次确认 |
| `M7-UI-004` | 录制态信息密度 | 录制开始默认简洁；支持简洁/详细切换；RED、IR、五项指标、滤波选择与直接操作尽量在同屏呈现；异常与 freshness 不被折叠 |
| `M7-UI-005` | 表单、动态字号与无障碍 | 数字键盘与焦点导航、IME bring-into-view、48 dp 操作目标、选择/波形/指标语义、字体放大自适应 |
| `M7-PERF-001` | Compose 状态与重组边界 | 状态提升到最低共同祖先，双视图单向数据流；高频波形不驱动会话/表单树；动态列表有稳定 key；移除 composition 后回写状态 |
| `M7-PERF-002` | 波形分配与稳定模型 | RAW 展示变换和 plot model 按发布序号/模式缓存或上游预计算；展示快照明确只读所有权；不在 Canvas 每帧重复分配同一绘图模型 |
| `M7-PERF-003` | 发布性能 | 增加可生成的 Baseline Profile 路径，覆盖启动、录制页、会话双视图与列表滚动；release R8 保留现有隐私/构建门禁并增加 profile 打包证据 |

## 3. 页面与状态设计

### 3.1 已保存会话

外层只保留一个 `SAVED_SESSIONS` 页面，内部状态为 `SavedSessionsViewMode.ARCHIVE` / `FLAT_FILES`。共同父级拥有：

- 当前视图模式；
- 选择模式和去重后的已选会话；
- 被试展开集合；
- 删除确认状态；
- 刷新、导出、删除、全选、取消与打开详情事件。

数据向下传递，事件向上传递。两个内容 composable 不再各自创建顶栏或决定返回语义。页面使用 `Scaffold`：

- `topBar`：返回、标题、刷新、视图切换、选择；
- 选择模式下使用独立 contextual action bar，避免在一行堆放四个文字按钮；
- 内容区只切换 archive/flat list；
- 导出为 primary，删除为 error，刷新为 icon/tertiary，取消为低强调；颜色之外仍保留文字/图标语义；
- 删除前显示去重后的会话数量与不可恢复提示。

archive 列表使用扁平 LazyList 模型：subject header、展开的 seq、unclassified session 都有稳定 key。subject 展开集合由共同父级持有，切换视图后仍可恢复。会话行只接收自身 UI 状态，不接收完整 `SessionsUiState`。

### 3.2 录制页

使用 `CaptureContentDensity.COMPACT` / `DETAILED`。录制开始事件把密度切到 `COMPACT`，用户可显式展开；停止后回到配置所需的详细状态。该转换由事件处理而不是 `LaunchedEffect` 在组合后回写。

录制时页面优先顺序固定为：

1. 紧凑连接/freshness 摘要与密度切换；
2. 固定直接操作区：血压记录、停止并保存、连接/断开需要时可达；
3. `RAW / CAUSAL / FIXED` 三等宽单选控件；
4. RED、IR 波形；
5. 心率、RR、PI、SQI、BP 五项指标。

简洁模式隐藏算法 profile、样本 cursor、说明性段落、权限/绑定诊断和指标来源详情，但绝不隐藏错误、无效原因、数据超时和操作进度。五项指标在正常字体宽度下使用一行五列；在字体放大或空间不足时允许横向滚动/自适应换行，不截断可访问性内容。

录制直接操作区进入 `Scaffold.bottomBar` 或等价固定容器，不再是可滚走的 LazyColumn item。配置表单继续可滚动，并增加数字键盘、Next/Done、焦点移动和 bring-into-view。

### 3.3 状态、不可变性与生命周期

- Activity 只负责路由和 Android launcher；live capture、saved sessions 分别形成较小 route/screen composable。
- 高频 `LiveWaveformSnapshot` 与低频连接、表单、会话状态隔离；只将所需 state slice 传给叶子。
- 标准 `List/Map/Set` 或 primitive array 未保证深层不可变时禁止仅为跳过重组而错误添加 `@Immutable`；UI 状态改用只读副本/稳定 UI model 后才能标注。
- `remember` 只缓存纯展示结果；波形缓存以 `publicationSequence`、显示模式、settling 和尺寸约束为 key。异步工作继续由 ViewModel/service scope 持有。
- 手工 BP dialog reference 提升到 `CaptureServiceViewModel` 的可观察 UI 状态，避免 Activity 配置变化丢失弹窗时间戳。
- `derivedStateOf` 只用于选中会话数量、上下文栏可见性等“输入变化频繁但结果变化较少”的轻量派生；滤波/降采样等重计算移出 composition。

## 4. 具体文件操作

### 4.1 新增文件

- `app/src/main/java/com/example/ppgcollector_android/SavedSessionsRoute.kt`
  - 共同 `Scaffold`、固定顶栏、视图切换、contextual selection bar、删除确认和视图状态。
- `app/src/main/java/com/example/ppgcollector_android/LiveCaptureScreen.kt`
  - 从 `MainActivity.kt` 移出录制页结构，定义信息密度、固定操作区、连接摘要和资料表单。
- `app/src/main/java/com/example/ppgcollector_android/LiveWaveformComponents.kt`
  - 滤波分段选择、RED/IR Canvas、紧凑/详细指标、稳定 plot 展示模型与无障碍语义。
- `app/src/test/java/com/example/ppgcollector_android/SavedSessionsUiPolicyTest.kt`
  - 视图/选择/去重计数和按钮策略的纯 Kotlin 测试。
- `app/src/test/java/com/example/ppgcollector_android/CaptureUiPolicyTest.kt`
  - 录制转换默认 compact、五指标/滤波标签和详情可见性策略测试。
- `baselineprofile/` 模块及测试源
  - Macrobenchmark/Baseline Profile generator，覆盖主要导航、列表与录制 UI；若无真机执行，本轮至少完成可编译、可安装和 release 打包合同，真实 profile 生成仍明确为设备门禁。

### 4.2 修改文件

- `MainActivity.kt`
  - 删除大型 `BleHome`、波形组件和未使用的旧 `SessionsPanel`；路由改为单一 Saved Sessions 页面；将 BP dialog state 事件交给 ViewModel；缩小根组合重组范围。
- `SessionsScreens.kt`
  - 保留 flat content 与详情/对比；移除 flat 自有 `PageHeader`；动态 Lazy item 增加语义 key；API 增加规范 `Modifier`。
- `SubjectArchiveScreen.kt`
  - 改为纯 archive content；移除自有顶栏和局部 `rememberSaveable(expanded)`；扁平化展开项，按 subject/directory key；行组件只接收必要状态。
- `SessionsViewModel.kt`
  - 暴露去重后的 selected session count/selection policy；保持删除 parent check；UI 触发确认后才调用删除。
- `CaptureServiceViewModel.kt`
  - 提升 BP dialog reference；录制开始/停止对外产生明确 UI transition event 或 recording generation，供 route 初始化信息密度。
- `core/signal/LiveWaveformRuntime.kt`、`core/signal/PpgDisplayTransform.kt`
  - 明确展示帧不可变所有权；消除 `filter` 装箱索引，提供按发布帧复用的 transformed/plot input。
- `app/build.gradle.kts`、`settings.gradle.kts`、`gradle/libs.versions.toml`
  - 接入 Baseline Profile/Macrobenchmark 所需插件与依赖；保持 release R8 优化并使生成 profile 随 release 打包。
- `AndroidMigrationPlanning/00_AGENT_MIGRATION_BRIEF.md`
  - 当前迭代更新为 M7.6，并将本文加入权威入口。
- `AndroidMigrationPlanning/docs/05_SOURCE_REFERENCE_INDEX.md`
  - 增加 M7.6 Compose/UI/performance Android 索引。
- `AndroidMigrationPlanning/status/DEVELOPMENT_STATUS.md`
  - 更新简版当前快照、验证事实和开放设备门禁。
- `AndroidMigrationPlanning/status/DEVELOPMENT_STATUS_DETAILED.md`
  - 末尾追加 M7.6 实现、唯一测试结果、风险与下一步；不改写历史。

## 5. 单轮实施顺序

1. **页面骨架**：先统一 Saved Sessions route、返回语义、固定 top bar 与 contextual selection bar；完成删除确认和实际会话计数。
2. **列表与状态**：扁平 archive LazyList、提升展开状态、减少完整 state 下传、为动态项配置稳定 key。
3. **录制 UI**：拆出 live capture screen，加入 compact/detailed、固定操作区、简化三滤波按钮和五指标布局、IME/焦点处理。
4. **重组与绘图**：隔离高低频状态，移除 composition 后回写，优化 raw transform/plot 分配，规范 `Modifier` 与 semantics。
5. **发布性能**：接入 Baseline Profile 编译/打包路径和主要用户旅程 generator；保留真机生成门禁。
6. **测试与文档**：补纯 Kotlin/Compose policy tests，更新 brief/index/两份状态；最后执行一次综合 Gradle gate。

## 6. 唯一综合校验门禁

开发期间只做静态阅读和编译前代码检查，不分批运行测试。所有实现和文档完成后仅执行一次：

```text
env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy :app:assembleRelease :baselineprofile:assembleNonMinifiedRelease --no-configuration-cache --no-daemon
```

门禁必须同时证明：

- JVM/policy/regression tests 通过；
- Compose/AndroidTest 源可编译；
- debug、release、androidTest 与 baseline profile generator 可构建；
- lint、R8、release privacy/API/FGS 既有合同不回退；
- 不改变 raw/CSV/sidecar 数据合同。

真实设备上的 Baseline Profile 生成、TalkBack、动态字号、IME、SAF、2 小时录制和帧时间属于明确延期的 hardware/runtime 门禁，不得在本轮无设备证据时宣称通过。

## 7. 完成定义

- 两种会话视图切换时顶栏位置、大小和返回语义稳定；
- 选择操作不挤压标题，删除前确认，计数对应实际去重会话；
- 录制默认 compact，直接操作固定可达，滤波按钮一行三列、指标正常字体下一行五列；
- 大字体/窄屏不以截断或不可点击为代价强制五列；
- 高频波形计算和重组被限制在波形/指标区域，动态列表具备稳定 identity；
- 旧 duplicate UI 删除，关键 Composable 具备规范 Modifier/API/semantics；
- baseline profile 构建路径、测试、brief/index/两份状态与单次综合门禁证据齐全；
- 本轮相关文件单独提交，用户 `.idea/*` 与 `app/release/` 不暂存、不修改。
