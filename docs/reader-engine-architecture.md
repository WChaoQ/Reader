# Reader 最终阅读引擎架构规范

> **状态：最终目标架构基线（Final Architecture Baseline）**
>
> 本文是 Reader 阅读引擎的唯一权威设计文档。新增格式、替换第三方库、重写分页或渲染实现，均必须遵守本文的边界与不变量。
>
> 本文定义最终目标和强制约束，不代表仓库中每一项能力都已实现。实现状态必须由代码和测试确认，不得把“设计完成”误报为“功能完成”。

## 1. 最终决策

采用 **格式独立引擎 + 统一文档协议 + 双布局管线 + 统一阅读会话**。

不是每种格式各自实现一套完整 UI、分页和进度系统，也不是把所有格式强行塞进同一种分页算法。

1. **一格式一适配器。** 格式模块负责识别、打开、解析、目录、内容、资源和格式特有定位；不负责 Compose UI、全局阅读状态或通用分页。
2. **统一项目自有协议。** 上层只认识项目定义的 Document、BookSource、Locator、TOC、内容块、资源引用和错误模型。Readium、PDFium、第三方 Markdown AST 等类型不得越过适配器。
3. **双布局管线。** TXT、Markdown、EPUB 进入统一 Reflow 管线；PDF 进入 Fixed Layout 管线。未来漫画、图片集等固定版式格式可复用 Fixed 管线。
4. **渲染消费布局结果。** 渲染层不重新解析源文件、不自行分页、不猜测定位；只绘制布局层产生的页面结果。
5. **统一阅读会话。** 一个打开的文档对应一个 Session，持有 Source、Layout/Render Engine、任务作用域和资源边界。关闭 Session 必须取消任务并释放资源。
6. **UI 只依赖稳定门面。** Reader 页面不判断 Readium、PdfRenderer 或 Markdown parser；格式差异由 Factory 和引擎内部消化。
7. **第三方依赖可替换。** 替换 EPUB 或 PDF 后端，只允许改动对应格式模块及其测试，不得迫使 UI、数据库或其他格式模块迁移。
8. **渐进式、可验证。** 先固定契约，再逐格式迁移。任何重构都必须有契约测试和真实文件回归。

## 2. 总体架构与依赖方向

目标模块关系：

- App：应用入口、Navigation、Hilt 组合根；装配各 Feature、Core 和 Engine 实现。
- Feature：Library、Reader、Settings。Reader 只依赖项目自有 Reader 门面、Core Model 和 DesignSystem。
- Core：Model、Data、Database、DataStore、Storage、DesignSystem、UI。负责业务数据、持久化和 SAF 文档访问，不解析格式、不分页。
- Engine Format API：DocumentHandle、FormatHandler、BookSource、统一错误与能力协议。
- Engine Format 实现：TXT、Markdown、EPUB、PDF。每种格式一个独立模块。
- Engine Layout：统一内容排版和两种布局策略：Reflow、Fixed。
- Engine Render：消费 Layout 结果并绘制页面；不依赖具体格式解析库。
- Benchmark：以 App 为目标，覆盖打开、首屏、翻页和内存。

依赖规则：

- App 可以依赖具体实现模块，负责注册和装配。
- Feature 不依赖具体格式实现或第三方解析库。
- Core Data 可以依赖格式打开门面，但不依赖 Layout、Render 或 Feature。
- Format 实现依赖 Format API、Core Model 和各自必要的第三方库。
- Layout 依赖项目内容模型，不依赖具体格式模块。
- Render 依赖布局输出和 Android 绘制 API，不依赖 Readium、Markdown AST 或 Feature。
- 格式实现之间禁止互相依赖。
- Core Model 不暴露第三方库类型、Activity、View、Room Entity。
- 不把可变 Session、Canvas、Bitmap、StaticLayout 或文件句柄放进 Room/DataStore。

模块拆分原则：当前仓库已有 format-api、layout、render，不要求为了命名立即新建 reader-api 空模块。公共 Session/Factory 应先在现有边界内整理；只有依赖方向和复用价值明确后才拆模块。禁止为了“未来可能用到”预建空模块和多层代理。

## 3. 格式引擎：一格式一适配器

### 3.1 格式模块职责

每个格式模块只负责：

1. 根据扩展名、MIME 和有限 magic bytes 识别格式。
2. 使用 DocumentHandle 打开用户选择的原始 URI。
3. 读取元数据、目录、文档结构和格式内部定位信息。
4. 按需提供内容单元（章节、页面或结构节点）。
5. 按需打开图片、字体等关联资源。
6. 将格式内部定位转换为项目 Locator，并支持反向恢复。
7. 管理第三方库对象、底层文件句柄和格式内部缓存。
8. 将底层异常映射为项目错误模型。
9. 在 close 时释放本格式拥有的资源。

格式模块不负责文字测量、分页算法、全局页码、阅读 UI、翻页动画、Room、DataStore、书架排序或阅读设置。

### 3.2 稳定格式协议

当前 format-api 中的 FormatHandler、BookSource、DocumentHandle 是基础契约。后续演进必须保持以下语义：

- FormatHandler 无状态、可复用；不能保存某本书的可变状态。
- canHandle 快速、无副作用，不打开完整文档、不解压、不做重解析。
- open 创建独立 Source；成功后 Source 拥有本次打开的底层资源。
- BookSource 表示一次打开会话，不是全局单例；不可跨文档复用。
- 章节/页面索引从 0 开始；会话内结构索引稳定。源文件发生变化后必须重新打开和校验。
- openResource 返回的流由调用方关闭；Source 负责关闭自己持有的 Publication、Renderer、Descriptor 等资源。
- 耗时解析支持协程取消；CancellationException 必须继续向上传递。
- 同一 Source 的并发能力必须明确。底层库非线程安全时，由适配器内部 Mutex 或串行队列保护。
- 不支持的能力返回明确的 Unsupported/NotAvailable，不伪造目录、页数或文本。

建议统一错误类别：UnsupportedFormat、InvalidDocument、PermissionRevoked、SourceUnavailable、CorruptDocument、ResourceNotFound、UnsupportedCapability、OutOfMemoryRisk、Cancelled、InternalFailure。UI 根据类别决定提示和恢复动作，不显示第三方堆栈。

### 3.3 各格式最终职责

| 格式 | 格式模块输出 | 后续管线 | 格式特有处理 |
|---|---|---|---|
| TXT | 文本章节、段落 Block | Reflow | 编码检测、换行、超长文本分块 |
| Markdown | 结构化 Block、Inline Span、资源引用 | Reflow | Markdown/GFM 解析、本地相对资源 |
| EPUB | 元数据、TOC、reading order、章节 Block、资源 | Reflow | ZIP、OPF、manifest、导航、XHTML、字体和图片 |
| PDF | 页数、页面尺寸、页面渲染请求、可选文本层 | Fixed | 随机访问、页面渲染、文本提取、页面旋转 |

TXT、Markdown、EPUB 最终进入同一套项目 Block 模型，但内部 Parser 不必相同。PDF 不得为了复用 Reflow 而被转换成整本图片或伪造章节文本。

## 4. 统一内容模型与定位

### 4.1 内容模型

格式适配器将格式专属 AST 转换为项目自有内容模型。至少覆盖 Paragraph、Heading、Quote、ListItem、CodeBlock、Divider、Image/Resource，以及 Bold、Italic、Code、Link 等 Inline Span。

约束：

- Block 表达语义，不直接携带 Compose UI。
- Span 的 start/end 基于转换后最终文本的 UTF-16 索引，并通过测试保证。
- 图片保存资源引用和替代文本，不在解析阶段把完整位图塞入 Block。
- 表格、公式等复杂节点必须明确表示为结构化 Block 或可控降级节点，不能静默丢内容。
- 不支持的节点保留可读降级内容，并记录解析诊断。
- 默认不把整本 Block 树持久化到 Room。

### 4.2 Locator 是唯一持久化阅读位置

统一 Locator 至少包含章节/页面索引、字符 offset、可选 progression，并通过书籍 ID 关联文档身份。未来可增加项目自有 fragment/anchor，但不能直接持久化第三方 Locator。

规则：

- Room 只保存项目 Locator 字段。
- Reflow 文档以章节 + 字符偏移恢复；PDF 以页索引恢复。
- progression 是结构变化后的近似回退信息，不是唯一恢复依据。
- 全局数字页码是布局结果，不是持久身份；布局变化后必须从 Locator 重新计算。
- TOC 项必须映射到可恢复 Locator；目录父节点不是正文资源时，定位到第一个有效子节点。
- 索引越界时进行有界校正，并记录恢复结果。

## 5. 双布局管线：共享协议，不共享错误算法

### 5.1 Reflow 管线（TXT / Markdown / EPUB）

数据流：

BookSource → loadChapter → Project Blocks / Inline Spans / ResourceRefs → DocumentTextBuilder → Styled Text / Layout Nodes → LayoutConfig + Paginator → PageSlices → Reflow Renderer → Canvas。

职责：

- DocumentTextBuilder：将 Block 转为唯一排版输入，统一处理样式和偏移。
- Paginator：负责切页、章节边界和分页事件，不负责绘制。
- LayoutConfig：集中承载所有影响布局的参数和算法版本。
- PageSlice：描述页面覆盖的行区间和字符区间，不复制整页正文。
- ReflowEngine：协调章节加载、分页进度、页索引、Locator 映射和布局缓存。
- Renderer：只消费同一份布局结果，不得再次独立测量文本。

分页和绘制必须使用完全相同的文本、字体、Span、宽度、行距、段间距、内边距和测量算法。当前仓库使用 Android StaticLayout；它是实现选择，不是对外 API。未来若改用 Compose TextMeasurer 或其他排版器，必须作为独立迁移，证明分页和绘制使用同一套测量结果，并通过行边界/视觉回归测试。禁止分页使用 StaticLayout、绘制却用 Compose Text 重新排版。

渐进式分页规则：

- 优先计算当前 Locator 所在章节和相邻章节。
- PageCount 区分 exact、estimated、available；估算值不得冒充精确值。
- 前置章节未完成时，全书页码可能变化；UI 保存 Locator，不保存瞬时数字页码。
- 每章 PageSlice 必须连续、无重叠、无遗漏，覆盖全部有效文本。
- 每次分页迭代至少前进一行；超高单行也必须能产生一页，避免死循环。
- 空章节、空文档和仅有图片/资源的章节必须有明确行为。
- 章节加载、分页、布局构建均可取消；旧 generation 的结果不得写入当前会话。

### 5.2 Fixed Layout 管线（PDF）

数据流：

DocumentHandle → PDF Format Adapter → PDF Document Session（page count / page size / text layer）→ Fixed Layout Engine → Page Index + Page Geometry + Render Request → Bounded Bitmap Cache → Fixed Renderer → Canvas。

规则：

- PDF 页数由后端提供，是精确值；页面索引从 0 开始。
- PDF 不进入 Reflow Paginator；页面尺寸、旋转和裁剪框由 PDF 页面本身决定。
- 页面位图按目标尺寸按需渲染，优先当前页，再预取相邻页。
- 位图缓存必须有字节上限；缓存淘汰不得回收正在 Canvas 绘制的 Bitmap。
- 同一 PdfRenderer 的页面访问按后端线程安全要求串行化。
- 页面渲染任务可取消；文档切换后迟到的位图不得进入新会话。
- 页面加载中保留上一张已显示页面，避免先清空导致闪屏。
- 页面缩放/旋转/夜间滤镜属于呈现参数；只有影响位图内容的参数才进入缓存 key。
- PDF 文本提取是独立可选能力，不得阻塞页面显示，也不能假设所有 PDF 都有文本层。

### 5.3 统一页面门面

两条管线向 Reader UI 暴露相同的最小能力：

- pageCount（exact / estimated / available）
- ready 状态
- locatorOf(pageIndex)、pageIndexOf(locator)
- 请求/绘制指定页面
- 可选 pageText、hitTest、selection capability
- close()

两条管线内部可提供不同能力。某格式不支持的能力必须显式标记，不得以空字符串或假数据让 UI 猜测。

## 6. Reader Session：生命周期唯一所有者

最终由 Session Factory 创建阅读会话。Session 是一个文档从打开到关闭的资源边界。

目标契约示意（不要求照抄为同名类，应优先复用现有 BookSource / ReaderEngine）：

- ReaderSession：暴露 DocumentInfo、Source、Layout、Capabilities、metadata、TOC、Locator 映射和 close。
- ReaderSessionFactory：接收 DocumentHandle、initial Locator、LayoutOptions，返回一个完整可用的 Session。
- Reader UI/ViewModel 只持有当前 Session 的稳定门面，不直接持有第三方对象。

创建顺序：

1. 校验文档授权与源文件可访问性。
2. 通过 FormatRegistry 选择唯一 Handler。
3. Handler 打开 Source 并读取最小必要元数据。
4. 根据格式能力选择 Reflow 或 Fixed 管线。
5. 将初始 Locator 转换为当前布局页索引。
6. 预热当前页及相邻页，首屏 ready 后交给 UI。
7. Session 持有 Source、Engine、任务 Job、资源流和缓存句柄。
8. 关闭时先标记 closed，再取消新任务，等待正在使用资源的任务退出，最后关闭 Engine、Source、Descriptor 和资源流。

所有权规则：

- 谁创建资源，谁负责释放；所有权转移必须写在接口契约中。
- UI 不负责关闭 Publication、PdfRenderer、PFD 或 InputStream。
- 不允许异步 cleanup 与资源仍被使用发生竞态。
- close 必须幂等。
- Session close 后任何新请求都返回 Closed/Cancelled。
- 打开失败时按创建顺序逆序释放已创建资源，避免部分初始化泄漏。

## 7. 缓存策略

缓存全部可重建，并按职责分层：

| 缓存 | 内容 | Key | 生命周期 |
|---|---|---|---|
| Source cache | 元数据/目录轻量缓存 | URI identity + source fingerprint | 有界；源变更失效 |
| Parse cache | 可选章节结构/Block | document fingerprint + parser version | 按需 |
| Page slice cache | 分页切片，不含完整布局对象 | document fingerprint + layout hash | 可落盘、可重建 |
| Layout memory cache | StaticLayout/页面布局对象 | session + chapter + layout hash | 内存 LRU |
| Bitmap cache | PDF 页面位图 | document + page + render params | 字节上限 LRU |
| Resource cache | 封面/图片/字体临时资源 | document + resource ref | 可清理 |

强制规则：

- 原始文档始终由用户 DocumentProvider 持有；不复制整本书。
- 不把 Bitmap、StaticLayout、Publication 或第三方对象写入 Room。
- 缓存 key 包含源文件身份/可验证指纹、解析器版本或分页算法版本，以及全部影响结果的参数。
- 无法可靠获得源文件修改信息时，不得仅凭 URI 认定内容永远不变；重新打开时轻量验证，必要时使缓存失效。
- 缓存损坏、版本不匹配、内容越界时安全丢弃并重建。
- 内存压力时优先释放非当前章节布局和非当前 PDF 位图。
- 缓存目录不得成为唯一数据来源。

## 8. SAF 与文件访问边界

- 仅通过 ACTION_OPEN_DOCUMENT 获取用户明确选择的 URI，并持久化只读授权。
- URI 是文档身份，不是文件系统路径；统一通过 ContentResolver/DocumentHandle 访问。
- DocumentHandle 负责打开 InputStream/PFD，不负责解析格式。
- PDF 和需要 ZIP 随机访问的 EPUB 必须确认 provider 提供可 seek descriptor；不支持时给出明确错误，不偷偷复制整本源文件。
- 源文件不可访问、授权撤销或 provider 离线时，保留书架记录，提示重新授权/重新选择。
- 文件名、ZIP entry、HTML href、Markdown 图片路径均是不可信输入，必须规范化并校验边界。
- 不允许格式解析器直接联网拉取 EPUB/Markdown 外部资源；本项目是本地阅读器。

## 9. 并发、错误与稳定性

### 并发规则

- 主线程只负责 UI 状态和轻量协调；解析、I/O、分页和位图渲染在合适 Dispatcher 执行。
- 使用结构化并发；每个 Session 有自己的任务作用域或 generation token。
- 文档切换先使旧 generation 失效，再启动新 Session。
- 不同文档可独立打开；同一底层非线程安全对象由 Adapter 内部串行化。
- 禁止 GlobalScope。
- CancellationException 必须继续抛出，不能当成普通解析失败吞掉。
- 回调必须校验 Session 是否仍有效，避免旧会话污染当前 UI。

### 错误恢复

| 错误 | 处理 |
|---|---|
| UnsupportedFormat | 不登记或提示格式不支持 |
| PermissionRevoked / SourceUnavailable | 保留书籍记录，提示重新授权/选择 |
| InvalidDocument / CorruptDocument | 提示文件无效，不崩溃 |
| ResourceNotFound | 显示资源占位，不影响其他章节 |
| UnsupportedCapability | 隐藏对应操作或提示不支持 |
| OutOfMemoryRisk | 降低渲染尺寸、缩小缓存、释放非当前资源 |
| Cancelled | 静默结束，不显示错误 |
| InternalFailure | 记录诊断信息，UI 展示可恢复错误 |

禁止 catch Throwable 后无条件吞掉异常。必须区分取消、预期格式错误和真正程序错误。

## 10. 第三方库与替换策略

第三方库只存在于对应 Adapter 内部：

| 能力 | 当前/可选实现 | 隔离位置 |
|---|---|---|
| EPUB Container / OPF / Navigation | Readium Kotlin Toolkit | format-epub |
| PDF 页面渲染 | Android PdfRenderer；未来可评估 AndroidX PDF/PDFium | format-pdf / Fixed Adapter |
| Markdown AST | 当前 Markdown parser；未来可替换解析库 | format-markdown |
| TXT 编码识别 | 项目实现或专用检测库 | format-txt |
| Reflow 排版 | 当前 StaticLayout；未来可评估其他测量后端 | layout |
| Compose UI | Jetpack Compose | feature-reader |

替换库必须满足：

1. 不把第三方类型添加到公共协议。
2. 不更改持久化 Locator 语义；若必须改变，提供版本化迁移和旧数据恢复。
3. 不改变 UI 的打开、翻页、缩放和浏览交互。
4. 新旧实现使用同一批 fixture 做输出对比。
5. 通过资源释放、取消、损坏文件、超大文件、随机访问和真实设备测试后，才移除旧实现。
6. 不同时替换解析、分页、渲染三层；一次只替换一个边界。

## 11. 测试策略与验收条件

### Format Adapter Contract Tests

每种格式必须覆盖：正常文件、空文件、损坏文件、错误扩展名/MIME、元数据、TOC Locator 可恢复、索引边界、资源缺失降级、重复打开关闭、取消任务、句柄释放和超大文件按需读取。

### Reflow Tests

覆盖空文本、超长单行、连续空行、长章节、段落边界、标题、列表/嵌套列表、引用、代码、图片、链接、表格/公式降级；Span 偏移与最终文本一致；PageSlice 连续无重叠无遗漏；布局参数变化正确使缓存失效；取消和快速跳章安全；分页与绘制行边界一致。

### Fixed Tests

覆盖空/单页/多页 PDF、损坏/加密 PDF、超大页面、旋转和不同尺寸；首屏等待目标位图 ready；切页期间旧页不闪白；LRU 淘汰与 Canvas 绘制并发安全；快速翻页、取消、close 竞态和位图内存预算。

### Session / UI Tests

覆盖 A 切换到 B 后 A 的任务不能覆盖 B；打开失败时资源全部释放；重复 close 安全；从 Locator 恢复而非依赖旧页码；旋转/进程重建/设置变化后恢复位置；保持既定交互：全屏阅读、点击中间进入页面浏览模式、横向排列相邻页面、滑动切换。

### Fixture 与性能基线

维护短/长 TXT、常见 Markdown、含嵌套 TOC/图片/字体 EPUB、扫描 PDF、文本 PDF、超大页面 PDF 和损坏文件。记录打开耗时、首屏耗时、分页耗时、峰值内存、缓存命中率和连续翻页丢帧。性能优化必须基于同一批 fixture 比较。

## 12. 新格式与模块演进规则

新增格式时只新增格式实现模块、Handler/Source/Adapter、fixture 和 contract tests，并在 App 组合根注册。

除非格式带来新的布局语义，否则不得新增独立分页模块或独立 Reader UI。新增布局类型时扩展布局协议和 Renderer，不得在格式模块中实现 UI。

禁止为了“未来可能用到”预建空模块、空接口或多层代理。抽象必须至少有两个真实使用方，或能清晰隔离一个第三方库/系统资源边界。

## 13. 当前仓库基础与待完成差距

以当前分支源码为准，仓库已经具备：

- format-api：DocumentHandle、FormatHandler、BookSource、FormatRegistry。
- 独立 TXT、Markdown、EPUB、PDF 格式模块。
- EPUB 已通过 Readium Adapter 接入，第三方类型集中在 EPUB 实现内部。
- layout：LayoutConfig、Paginator、PageSlice、StaticLayout 分页基础。
- render：ReaderEngine、ReflowEngine、FixedEngine 两种引擎。
- Core / Feature / App 分层与 SAF URI 来源策略。

以下是必须逐步落实的目标，不得假设已全部完成：

1. 把 Reader Session / Factory 的所有权、创建顺序和关闭顺序落实为可测试实现。
2. 补齐统一 capability、错误模型和定位协议，避免用空值表达不支持能力。
3. 审核 Source 与 ReflowEngine 的资源所有权，确保并发章节加载与 close 不发生竞态。
4. 固定 Block/Span 语义和偏移约定，补足复杂 XHTML/Markdown 节点测试。
5. 固定布局缓存指纹与源文件变化校验策略。
6. 完成 Reflow 与 Fixed 两条管线端到端契约测试。
7. 逐步将 Feature 对具体引擎类的认知收敛到 Session/Engine Facade；不为此一次性重写 UI。
8. 用真实设备和 fixture 建立首屏、翻页、内存与稳定性基线。

### 13.1 架构落地记录

**2026-11-11：阶段 B — Session 生命周期与创建边界**

已完成：
- 新增 `:engine:render` 的 `ReaderSession`，以原子状态保证关闭幂等；关闭后拒绝读取 Engine / Source 或提交新任务。
- 新增 `ReaderSessionFactory`，集中负责 PDF / Reflow 引擎选择、资源获取、初始化和部分初始化失败回滚；ReaderViewModel 不再直接构造格式引擎。
- Session 持有当前 Engine、可选 BookSource、TOC 快照和专属 SupervisorJob；ReflowEngine 使用 Session 专属任务作用域，不再使用 ViewModel 的全局作用域。
- 全文搜索也作为 Session 子任务运行；Session 关闭会先取消并等待全部子任务结束，再释放底层 Source / PDF Descriptor。
- 资源所有权明确：ReflowEngine 接管并关闭 BookSource；FixedEngine 接管并关闭 ParcelFileDescriptor；Session 不重复释放底层资源。
- 新增 `ReaderEngine.awaitPage` 和 `closeAndJoin` 统一能力；ReaderViewModel 不再判断 FixedEngine 类型。
- Reflow / Fixed 引擎均支持等待关闭完成；Reflow 关闭时串行化任务注册与关闭快照，避免新布局任务漏出清理集合；Fixed 关闭时等待 PdfRenderer 使用互斥锁退出后再释放 Descriptor。
- ReaderViewModel 只保存 Session；Engine 是从 Session 派生的只读访问属性，不再单独持有 Engine 或 Source。
- 打开流程通过 Mutex 串行化；generation token 阻止旧会话的布局回调和页数更新污染新会话。
- 新文档打开前先从 ViewModel 脱离旧 Session，等待旧 Session 的任务与资源全部清理，再创建新会话。
- 新增 Session 幂等关闭、任务取消、关闭后访问拒绝、关闭等待任务退出等单元测试；GitHub Actions 已纳入 Render Engine 单元测试。

阶段 B 尚需验收：
- 补充 ReaderSessionFactory 的资源回滚测试，以及快速连续切换、取消打开、PDF 渲染中关闭等集成/设备测试。
- 需要在用户手动触发 GitHub Actions 后确认编译和测试结果；本次没有触发构建。
- 确认不同 DocumentProvider 下 PDF / EPUB 随机访问和权限撤销的异常路径。

阶段边界：
- 不进入 Reflow 分页优化、Fixed 位图缓存优化或格式适配器替换；这些属于后续阶段。
- 不替换 Readium、PdfRenderer、StaticLayout，不改变既定阅读界面与交互。
- 阶段 B 的代码主干已落地，但在构建和真实设备验收前不标记为最终通过。

## 14. 实施顺序（必须逐阶段验收）

**阶段 A：冻结协议**
- 审核 Format API、Locator、Block、ReaderEngine。
- 补齐 API 文档与契约测试。
- 禁止新增格式把第三方 API 泄漏到 Feature。

**阶段 B：稳定 Session 生命周期**
- 建立唯一 Session Factory。
- 实现打开失败回滚、close 幂等、任务取消和 generation 隔离。
- 使用当前格式实现，不替换底层库。

**阶段 C：稳定 Reflow**
- 固定 Block/Span 和 DocumentTextBuilder。
- 统一分页与绘制的测量输入。
- 完成 PageSlice 不变量、渐进分页和缓存失效测试。

**阶段 D：稳定 Fixed**
- 固定 PDF 页面请求、位图缓存和渲染任务所有权。
- 验证首屏 ready、翻页无闪屏和内存上限。

**阶段 E：逐项评估替换**
- 仅在确有收益时评估新的 EPUB/PDF/Markdown/排版后端。
- 每次只替换一个 Adapter 或一个管线组件。
- 通过同一 fixture 和性能基线后再移除旧实现。

**最终验收标准：** 新增格式不会修改 Reader UI；替换第三方解析库不会修改 Locator 持久化、书架数据库和其他格式；快速切换文档不会出现旧任务污染、新旧资源竞态或泄漏；布局参数变化只使相关缓存失效；真实文档反复打开、翻页、关闭后保持稳定。

## 15. 变更控制

- 本文是引擎架构权威规范；若 reader-architecture.md 与本文冲突，以本文为准。
- 修改稳定协议、Locator 持久化语义、模块依赖方向或资源所有权，必须先更新本文并增加迁移说明。
- 普通 bug 修复不需要修改架构文档。
- 文档中“当前已实现”和“目标要求”必须分开陈述。
- 任何会改变用户阅读交互的改动，必须先说明交互差异并获得确认。
