# 西红柿下载器 / tomato_downloader

Android 小说搜索与下载工具，支持 TXT / EPUB、章节范围选择、下载进度、暂停与继续、下载目录设置和缓存管理。

## 下载与安装

前往 [GitHub Releases](https://github.com/2857865859/tomato_downloader/releases/latest) 下载正式版 APK。

当前正式版：**1.0.1**。支持 Android 8.0（API 26）及以上。

1. 下载 `tomato_downloader-v1.0.1.apk`。
2. 在 Android 设备上打开 APK，按系统提示安装。
3. 搜索书名、书籍链接或书籍 ID，在详情页选择章节范围与 TXT / EPUB 格式。
4. 可以在下载页管理任务，在设置页选择保存目录。

## 公开源码范围

本仓库公开部分 Android 应用源码和资源，供查看与学习：

- `MainActivity.java`：应用界面。
- `DownloadEngine.java`：下载任务状态与 WorkManager 调度。
- `AppDatabase.java`、`DownloadDao.java` 和实体类：Room 数据存储。
- `CoverStore.java`、`CacheManager.java`：缓存管理。
- `BookExporter.java`：TXT / EPUB 文件导出。
- `DownloadValidation.java`：章节范围校验。
- `res/`：界面样式、图标与应用标识。

详情、目录、正文等接口客户端、正文下载执行器、并发请求调度、授权服务实现和网络地址配置不公开。接口研究记录、测试数据、签名密钥与本地配置也不包含在本仓库中。

**这是部分源码仓库，保留了对私有组件的引用，不能独立构建完整应用。使用应用请下载 Releases 中的正式安装包。**

本仓库使用独立的 Git 历史，仅包含经过筛选的公开文件。
