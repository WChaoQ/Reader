# Reader Android 架构设计

> 架构基线：以当前仓库 Gradle 配置、Manifest、README 和可读取源码为准。
> 产品定位：纯本地 Android 阅读器，支持 EPUB、PDF、Markdown、TXT。
> 目标：可靠读取本地文档、低资源占用、边界清晰、易测试和可扩展。

本文区分已核验的工程事实与架构约束。Gradle 中声明了某模块，不代表其中所有功能均已完整实现。

## 1. 产品边界与设计原则

### 产品边界

- 仅处理用户主动选择或授权访问的本地文档。
- 通过 Android Storage Access Framework（SAF）让用户选择文档；当前导入流程将文件复制到应用私有目录并以内容哈希建立书籍 ID。
- 支持 EPUB、PDF、Markdown、TXT。
- 书籍索引、阅读进度和偏好保存在本地。
- 不包含账号、云同步、在线书城或远程内容抓取。
- 当前 Manifest 未声明 INTERNET 权限；新增功能不得擅自引入网络依赖。

### 设计原则

1. 关注点分离：UI、数据、文件访问、格式解析、布局和渲染各自负责单一职责。
2. 单向数据流（UDF）：界面发出事件，状态持有者处理并发布不可变 UI State。
3. 单一数据源（SSOT）：Room 管理持久化书籍数据，DataStore 管理偏好，ViewModel 管理短期页面状态。
4. 依赖倒置：上层依赖稳定 API，不依赖格式解析器的内部实现。
5. 按需加载：避免一次性读取整本书、解压整个 EPUB 或长期持有全部 PDF 位图。
6. 生命周期安全：Activity、Composable 和 ViewModel 不应成为持久数据的唯一持有者。
7. 最小化模块化：只有存在清晰职责和依赖价值时才拆分模块。
8. 性能优化必须由 Macrobenchmark、内存观察和真实文档测试支撑。

## 2. 总体架构

采用 Android 官方推荐的 UI / Data 分层，并结合当前仓库的 Core、Engine、Feature 模块。

    App（入口、根导航、依赖装配）
       ├── Feature（Library / Reader / Settings）
       │      ├── Core（Model / Data / Storage / Database / DataStore）
       │      ├── Core UI（Design System / 通用 UI）
       │      └── Engine（Format API / Layout / Render）
       ├── Core
       └── Engine 格式实现（TXT / Markdown / EPUB / PDF）

    Data → Database / DataStore / Storage / Format API
    Format 实现 → Format API
    Layout → Format API / 统一模型
    Render → Layout / 统一模型
    Benchmark → App

上述为目标职责和依赖方向，不代表所有箭头均已由当前 Gradle 依赖完全落实。实际依赖以各模块 build.gradle.kts 为准。

### 分层职责

| 层 | 职责 | 不应承担 |
|---|---|---|
| App | Application、Activity、根导航、依赖装配 | 解析文档、复杂业务 |
| Feature | 页面 UI、ViewModel、用户交互 | 直接访问 DAO、DataStore 或底层文件 API |
| Data | Repository、数据协调、持久化映射 | Compose UI、页面布局 |
| Storage | SAF、URI 授权、文件流和描述符 | 书籍业务规则 |
| Engine | 格式解析、统一内容、布局和渲染 | 书库和设置业务 |
| Model/Common | 跨模块模型和真正通用能力 | 依赖具体 Feature |
| DesignSystem/UI | 主题、设计令牌、通用组件 | 业务数据访问 |

Domain / UseCase 层不是强制层。只有当业务逻辑被多个 ViewModel 复用，或单个 ViewModel 复杂度确实过高时再引入，不要机械地为每个 Repository 方法增加 UseCase。

## 3. Gradle 模块职责

以下模块均已在当前 settings.gradle.kts 中声明。

### 3.1 App

模块：:app

- ReaderApplication、MainActivity 和应用级 Manifest。
- Hilt 根组件与应用级依赖装配。
- 应用主题、Edge-to-Edge 和根导航。
- 组合 Feature 页面及需要装配的 Engine 实现。

当前可见 MainActivity 通过 ReaderNavHost 进入应用。Activity 只负责宿主和系统窗口集成，不承载导入、解析、分页等业务逻辑。

### 3.2 Core

| 模块 | 职责 |
|---|---|
| :core:model | 纯 Kotlin 跨模块模型。当前可见 Book、BookFormat、Locator、BookMetadata、TocEntry、ResourceRef。 |
| :core:common | 真正跨模块复用的通用基础能力；避免成为无边界杂物模块。 |
| :core:database | Room Database、Entity、DAO、Migration 和 schema。 |
| :core:datastore | DataStore 偏好设置读写与 Flow 暴露。 |
| :core:storage | SAF 文档 URI、持久化 URI 授权、文件访问、输入流/描述符和必要缓存管理。 |
| :core:data | Repository 实现，协调数据库、设置、文件存储和格式 API。 |
| :core:designsystem | Material 3 主题、颜色、字体、形状和设计令牌。 |
| :core:ui | 不依赖具体 Feature 的通用 Compose UI 组件。 |

Model 应保持纯 Kotlin。Room Entity 不应直接成为 UI 模型；Storage 不应把临时文件路径误当作原始文档身份。

### 3.3 Engine

| 模块 | 职责 |
|---|---|
| :engine:format-api | 格式解析能力的稳定契约及统一内容/定位模型。 |
| :engine:format-txt | TXT 编码、文本读取和内容组织。 |
| :engine:format-markdown | Markdown 源文档读取、语法结构转换和资源引用。 |
| :engine:format-epub | EPUB 容器、元数据、目录、阅读顺序、章节和资源解析。 |
| :engine:format-pdf | PDF 打开、页数/页面信息读取和按需页面内容提供。 |
| :engine:layout | 与具体 UI 解耦的布局、分页、定位和排版协调。 |
| :engine:render | 将统一阅读内容和布局结果转换为呈现所需的模型或组件。 |

格式模块必须通过 format-api 暴露能力，不应互相依赖，也不应依赖 Feature。新增格式应新增实现模块并接入统一契约。

### 3.4 Feature

| 模块 | 职责 |
|---|---|
| :feature:library | 书库列表、空状态、导入入口、书籍操作和书库 UI 状态。 |
| :feature:reader | 阅读页面、目录/进度/翻页交互、阅读 UI 状态和引擎接入。 |
| :feature:settings | 阅读偏好设置 UI、状态展示和修改入口。 |

Feature 可以依赖 Core 公共 API；Reader 可以依赖 Engine 公共 API。Feature 之间不应直接互相依赖，跨 Feature 导航由 App 层协调。

### 3.5 Benchmark

模块：:benchmark

当前配置使用 Macrobenchmark，并以 :app 为目标工程。基准场景应覆盖冷/热启动、书库加载、各格式打开、长文档滚动、章节切换、PDF 页面切换和内存峰值。

## 4. 核心数据流

### 4.1 导入

用户点击导入 → SAF 返回 content URI → ImportBookUseCase 通过 ContentResolver 读取输入流 → BookFileStore 复制文件并计算 SHA-256 → FormatRegistry 选择格式实现并读取元数据 → Room 保存书籍索引 → Flow 更新书库 UI。

约束：

- SAF 返回的 content:// URI 不是普通文件路径，必须通过 ContentResolver 打开。
- **当前实现会复制完整原文件**到应用私有目录 `filesDir/books/<bookId>/original.<ext>`，并以 SHA-256 内容摘要（截取前 32 位十六进制字符）作为书籍 ID。
- 当前实现不持久化源 URI，也不依赖长期 URI 授权；原始文件随应用数据存储，删除书籍时由 BookFileStore 删除。
- 该方案便于离线读取和稳定随机访问，但应用私有数据会随导入文件大小增长。若未来改为 URI 引用模式，必须单独设计持久化授权、源文件失效恢复和 PDF 随机访问，不能只删除复制步骤。
- 封面、章节索引和渲染缓存应与原始文件分开管理，并允许重建。
- 元数据提取失败不应导致崩溃；应保留可识别的文件名和格式信息。
- 导入应具备幂等性，避免重复记录。

### 4.2 打开和阅读

用户选择书籍 → Reader ViewModel → BookRepository 获取书籍记录和应用私有原文件 → PDF 使用 FixedEngine，重排文本通过 BookSourceRepository / FormatRegistry 分发至 TXT / Markdown / EPUB → Layout → Render → Compose 阅读界面。

格式差异应尽量在 Engine 内部消化。Feature 不应散落大量按格式分支的解析代码。

### 4.3 阅读进度

- 使用统一 Locator 表达阅读位置。
- 当前模型约定：重排文本使用章节与章节内偏移；PDF 使用页码作为 chapter，offset 为 0。
- progression 表示文档内相对进度，其计算和恢复语义必须明确。
- 进度保存采用有效位置变化与节流策略，避免每次像素级滚动都写数据库。
- 文档目录或内容变化后，恢复逻辑必须校验定位有效性；失效时回退到最近有效位置。

## 5. 模块依赖规则

### 目标方向

- :app 是组合根，可以依赖 Feature、Core 和需要装配的 Engine 实现。
- :feature:* 依赖 Core 公共 API；:feature:reader 可依赖 Engine 公共 API。
- :core:data 依赖数据源模块和 Format API，不依赖 Feature。
- :core:database、:core:datastore、:core:storage 不依赖 Feature。
- :engine:format-* 依赖 format-api 和必要基础模型，不依赖 Feature 或 Repository。
- :engine:layout、:engine:render 不依赖 Feature 业务状态。
- :core:model 不依赖 Android UI、Room、Hilt 或具体 Feature。

### 当前配置中值得治理的依赖

1. `:feature:reader` 已不再直接依赖 database、datastore、storage 或 common；书籍/进度/书签/设置/文档打开分别通过 `:core:data` 的 Repository API 访问。
2. `:feature:reader` 保留 format-api、layout、render 依赖：它需要统一 BookSource 类型并直接驱动布局与渲染，但不依赖具体格式实现。
3. :app 当前显式依赖多个格式实现和 Engine 模块，作为应用组合/装配层可以接受；若采用 Hilt multibinding 或 Engine Registry，应集中维护格式注册。
4. :core:data 当前依赖 format-api，应明确它只协调元数据/文档访问，不承担排版和渲染。
5. :core:common 只放真正跨模块通用能力。

以上是根据可见 Gradle 配置提出的治理建议，不代表运行时已经存在错误。

### 禁止事项

- Feature 直接访问 Room DAO、DataStore 实例或底层文件 API。
- Engine 依赖 Feature、Navigation 或 Compose 页面状态。
- 格式实现之间互相调用内部解析类。
- 将 Android Context、Uri、ParcelFileDescriptor 泄漏到纯 Kotlin 模型。
- 将 Entity、DAO 作为跨层公共模型。
- 使用全局单例保存可变阅读状态。
- 为了形式而机械增加只有一行转发逻辑的抽象层。

## 6. 格式引擎约束

### 统一契约

当前 format-api 通过 `FormatHandler`、`BookSource` 和 `FormatRegistry` 提供统一契约，并明确以下规则：

- `FormatHandler.canHandle` 只根据文件名和有限 magic bytes 判断格式，不执行 I/O。
- 每种 `BookFormat` 只能注册一个 handler；一个文件最多匹配一个 handler，避免依赖 Hilt Set 的遍历顺序。
- 打开文档并读取元数据。
- 获取目录或章节结构（格式支持时）。
- 按需读取章节内容或 PDF 页面。
- 将格式内部位置转换为 Locator，并支持从 Locator 恢复。
- `BookSource` 章节/页面索引统一从 0 开始；`chapterCount` 在当前文档会话内保持稳定。
- `BookSource.close()` 由打开会话的调用方负责；`openResource()` 返回的 InputStream 由调用方关闭。
- 关闭文档并释放底层资源。
- 将解析失败转换为可处理错误，不让异常直接穿透到 Compose。

统一模型应保持最小且稳定，格式私有结构留在对应模块。

### 各格式策略

**TXT**
- 明确字符编码策略，避免默认 UTF-8 导致乱码。
- 大文件采用流式或分块读取。
- 段落与章节组织应可预测，避免一次性创建大量 UI 节点。

**Markdown**
- 保留原始文档引用，解析结果按需生成。
- 明确支持的 Markdown/GFM 语法范围。
- 图片等资源按本地 URI 或文档相对路径安全解析，不隐式联网。
- 长代码块、表格和长行不得撑爆阅读容器。

**EPUB**
- 分离 ZIP 容器、OPF 元数据、manifest、spine、导航目录和章节解析。
- 不默认将整本 EPUB 解压到应用私有目录。
- 优先建立轻量索引，章节按需读取；缓存必须可清理、校验和重建。
- 规范化 ZIP 条目路径，拒绝目录穿越。
- 对缺失资源、错误编码、损坏压缩包和异常目录提供降级行为。

**PDF**
- 以页为单位提供内容，不将整份 PDF 渲染为位图集合。
- 页面渲染支持目标尺寸、缩放和取消。
- 页面位图使用有界缓存，页面离开窗口或内存压力下及时释放。
- 正确关闭 PdfRenderer、ParcelFileDescriptor 等系统资源。
- 页码与 Locator 映射固定且有测试。

### 资源与并发

- 文件读取、解压、解析、分页和位图渲染不得在主线程执行。
- 使用结构化并发，任务作用域绑定文档会话或 ViewModel 生命周期。
- 文档切换时取消旧任务，避免旧结果覆盖新页面。
- 明确文件句柄和渲染资源所有权，避免重复关闭或泄漏。
- 缓存有容量上限，并区分可重建缓存和用户数据。
- 解析器必须处理取消、空文件、超大文件和损坏输入。

## 7. UI 状态与导航

每个页面使用 ViewModel 作为状态持有者，向 Compose 暴露不可变 UI State，并接收明确的 UI Action。

建议约束：

- UI 使用 collectAsStateWithLifecycle() 收集 StateFlow。
- ViewModel 不保存 Activity、View 或 Composable 引用。
- 一次性提示与持久 UI 状态分开建模。
- 用户操作通过 ViewModel 方法或 Action 进入状态处理流程。
- 不把整本正文放进高频变化的 UI State；内容应分页或分块供给。
- 根导航位于 App 层，Feature 提供页面入口。
- 导航参数传递稳定 ID、URI 或最小必要参数，不传递大型文档对象。
- 恢复所需的书籍 ID、Locator 应可保存或从持久化数据恢复。

## 8. 数据持久化与文件生命周期

| 数据 | 唯一事实来源 | 说明 |
|---|---|---|
| 书籍元数据和导入记录 | Room | 不以内存列表作为持久来源 |
| 阅读进度 | Room | 使用统一 Locator |
| 阅读偏好 | DataStore | 主题、字号、行距等 |
| 原始文档 | 应用私有目录 `filesDir/books/<bookId>/original.<ext>` | 当前导入流程复制完整原文件；删除书籍时一并删除 |
| 临时解析/渲染缓存 | 缓存目录或内存 | 可清理、可重建 |
| 页面临时交互状态 | ViewModel / SavedState | 不替代持久进度 |

文件生命周期约束：

- 仅通过 SAF 选择来源文件，并通过 ContentResolver 读取；不申请宽泛存储权限。
- 当前原文件保存在应用私有目录，启动阅读不再依赖源 URI 是否仍有效。
- 文件缺失时保留书籍元数据并展示可恢复错误；删除书籍时分别处理数据库记录和原文件/缓存。
- 应用私有文件可能参与系统备份；备份策略必须明确是否备份原始书籍，避免意外产生大体积备份。

## 9. 依赖注入与构建

### Hilt

- :app 持有 @HiltAndroidApp 应用入口。
- Activity 使用 @AndroidEntryPoint。
- Repository、数据源和格式实现通过构造函数注入。
- 格式实现可通过 Hilt multibinding 或显式 Registry 聚合，选择一种统一机制。
- 不在 Composable 中手动创建 Repository、Parser 或数据库实例。

### Gradle

当前工程使用 Version Catalog（gradle/libs.versions.toml）、Included Build（build-logic）、Convention Plugins、KSP。Room schema 输出目录配置在 :core:database/schemas。

版本升级应集中在 Version Catalog 和 build-logic。不要在模块内重复声明版本或随意覆盖公共 Android 配置。

## 10. 性能、稳定性和安全

### 性能

- 书库使用懒列表，避免一次性创建大量卡片。
- 阅读正文按章节、页或可视窗口增量加载。
- 不在 Composable 重组期间执行文件 I/O、解析、复杂转换或位图生成。
- PDF 位图和 EPUB 章节缓存设置明确上限。
- 大文档任务支持取消。
- 使用 Macrobenchmark 验证启动、页面打开、滚动和翻页性能。

### 稳定性

- 处理 URI 授权失效、文件被移动/删除、损坏文件、磁盘空间不足和进程回收。
- 数据库 schema 变更必须提供 Migration，不依赖 destructive migration 清空用户数据。
- 解析异常转换为明确错误状态，并保留可恢复的书库信息。
- 缓存损坏时提供校验和重建。
- 资源使用 use、明确生命周期所有者或等价安全机制释放。

### 安全与隐私

- 默认无网络能力，不添加 INTERNET 权限。
- 不上传用户文档、阅读记录或 URI。
- 不将完整文档内容、完整文件路径或敏感 URI 写入日志。
- 解析不可信文档时限制资源消耗，校验 ZIP 路径，避免无限递归和异常展开。
- 仅请求当前功能所需的 SAF 授权。
- 审查 allowBackup、备份规则和设备迁移行为，避免备份不可恢复缓存或不必要数据。

## 11. 测试策略

| 范围 | 重点 |
|---|---|
| Model | Locator 边界、进度映射、模型不变量 |
| Storage | URI 授权、文件不存在/不可读、流关闭 |
| Database | DAO、事务、Migration、索引 |
| Data | Repository、错误映射、Flow 更新 |
| Format | 编码、损坏文件、目录结构、超大文档、定位往返 |
| Layout | 分页边界、字号变化、定位恢复 |
| Feature | ViewModel 状态转换、用户事件、错误和空状态 |
| UI | Compose 交互、导航和无障碍 |
| Benchmark | 启动、书库、格式打开、滚动/翻页和内存表现 |

测试样本应覆盖每种格式的最小文件、正常文件、异常文件和大文件。EPUB 覆盖缺失资源、spine 顺序、嵌套目录和路径安全；PDF 覆盖加密/损坏/大页数；Markdown 覆盖长表格、代码块、图片引用和 GFM 边界。

## 12. 新增格式的标准流程

1. 检查 format-api 是否已有足够的统一契约，只在通用能力确实缺失时扩展。
2. 新建 :engine:format-<name> 模块，实现解析、资源管理和 Locator 映射。
3. 编写纯解析单元测试和真实文件样本测试。
4. 在应用装配层注册格式实现。
5. 必要时扩展导入识别和元数据流程，不把格式分支散落到 Feature。
6. 在 Reader UI 中复用统一内容和定位模型。
7. 增加大文件性能测试和资源释放测试。
8. 更新本文档和格式支持说明。

## 13. 当前工程状态与待核验项

### 已确认

- 单 Activity + Compose 根入口。
- Hilt 应用入口和 Activity 注入集成。
- Core、Engine、Feature、Benchmark 模块均已在 settings 中声明。
- Room、DataStore、Storage、Format API 和各格式实现已有独立 Gradle 模块。
- :core:model 包含 Book、BookFormat、Locator、BookMetadata、TocEntry、ResourceRef，以及数据库无关的 Bookmark、ReaderSettings。
- Manifest 未声明 INTERNET 权限。
- Benchmark 已配置 Macrobenchmark 目标工程。

### 需要结合完整实现持续核验

- 四种格式解析器的边界行为、异常模型和大文件性能。
- Room Entity/DAO/Repository 的数据关系与 Migration。
- 当前整文件复制策略的存储占用，以及未来是否需要可选 URI 引用模式。
- EPUB 是否按需读取章节且没有额外整本解压副本。
- PDF 页面缓存是否有上限、资源是否及时释放。
- ViewModel 是否统一使用 StateFlow/UDF 和生命周期感知收集。
- Hilt 格式注册是否集中且无重复绑定。
- 备份规则是否符合原始文件由用户持有的模型。

### 优先治理顺序

1. 已为 format-api 明确零基索引、资源所有权、关闭责任和 handler 唯一性，并为 FormatRegistry 增加单元测试。
2. 已将书签和阅读设置模型移入纯 Kotlin core:model；Reader Feature 不再接触 Room Entity 或 DataStore 实现。
3. 已通过 BookSourceRepository 和 BookRepository 隐藏格式注册与文件存储细节。
4. 继续为四种格式建立真实文件样本、异常输入、取消和资源释放测试。
5. 评估整文件复制的存储成本，并建立大文档内存与性能基线。

## 14. 官方规范与参考资料

- [Android 应用架构指南](https://developer.android.com/topic/architecture)：分层架构、UDF、SSOT、Repository 和生命周期。
- [Android 架构建议](https://developer.android.com/topic/architecture/recommendations)：Repository、Coroutines/Flow、ViewModel 和生命周期感知 UI 状态收集。
- [Data layer](https://developer.android.com/topic/architecture/data-layer)：Repository、数据源和单一数据源。
- [Domain layer](https://developer.android.com/topic/architecture/domain-layer)：UseCase 层为可选层，只在复杂逻辑或复用场景引入。
- [Android 模块化指南](https://developer.android.com/topic/modularization)：模块边界、封装、依赖和模块粒度。
- [Android 官方 Skills](https://github.com/android/skills)：面向 AI Agent 的 Android 技能和工作流指导。

## 15. 文档维护约定

- 模块结构、依赖方向或数据生命周期变化时同步更新本文档。
- “已确认”只描述已从仓库配置或源码核验的事实；计划能力不得写成已实现能力。
- 新增模块前先确认现有模块无法合理承载该职责。
- 跨模块 API 变更必须检查依赖方向、测试覆盖和公开类型数量。
- 架构质量以真实耦合和复杂度是否降低为准，不以模块数量或设计模式数量衡量。
