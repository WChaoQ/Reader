# 本地阅读器架构设计（最终版）

> 目标：**纯本地、高性能、低占用、易扩展、架构清晰**
> 格式：EPUB / PDF / Markdown / TXT
> 技术约束：仅 Kotlin；优先 Android 官方库（Jetpack）；官方没有的自行用 Kotlin 实现；不依赖第三方阅读项目
> 架构依据：Android 开发者官网《应用架构指南》（分层架构、单向数据流、单一数据源）、Compose 性能指南、模块化指南

完整架构文档请参见项目本地 `reader-architecture.md` 或仓库历史。工程骨架已按该文档实施（模块划分、Version Catalog、Convention Plugins、Room 表结构、Format 插件接口等）。

## CI

`.github/workflows/build.yml` 为 **workflow_dispatch** 手动触发，可选 debug / release。

## 本地构建

```bash
# 首次需生成 Gradle Wrapper jar
gradle wrapper --gradle-version 9.6.0
./gradlew :app:assembleDebug
```
