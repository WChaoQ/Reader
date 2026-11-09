# Reader

纯本地、高性能 Android 阅读器（EPUB / PDF / Markdown / TXT）。

架构见 [reader-architecture.md](docs/reader-architecture.md)。

## 技术栈

- Kotlin + Coroutines / Flow
- Jetpack Compose + Material 3
- Hilt (KSP)
- Room + DataStore
- Navigation Compose
- Gradle Version Catalog + Convention Plugins

## 模块

| 模块 | 说明 |
|------|------|
| `:app` | 入口、导航、Hilt 根 |
| `:core:*` | model / common / database / datastore / storage / data / designsystem / ui |
| `:engine:*` | format-api + 各格式插件、layout、render |
| `:feature:*` | library / reader / settings |
| `:benchmark` | Macrobenchmark + Baseline Profile |

## 构建

```bash
./gradlew :app:assembleDebug
```

## CI

GitHub Actions 工作流 `Build App` 为 **手动触发**（`workflow_dispatch`），可选择 debug / release。

## 约束

- 不声明 `INTERNET` 权限
- 文件仅通过 SAF 的 `ACTION_OPEN_DOCUMENT` 打开
- 新增文档只保存持久化 `content://` URI，不复制源文件；原文件仍由系统文件提供程序管理
- 最低 API 26，target / compile SDK 36
