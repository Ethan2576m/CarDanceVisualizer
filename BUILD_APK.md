# 如何生成 .apk（三步走）

> 一句话：**装 Android Studio → 打开本工程 → 点 Build APK**。下面是从零到装车的完整路径。

---

## 第 1 步：装环境（只做一次）

### 1.1 安装 Android Studio

1. 打开 https://developer.android.com/studio
2. 点 **Download Android Studio**，一路下一步安装即可。
3. 首次启动会弹出 Setup Wizard，选 **Standard（标准）**，它会自动下载：
   - Android SDK
   - Android SDK Platform（含 API 34）
   - Android SDK Build-Tools
   - Android SDK Platform-Tools（含 adb）
   - 模拟器（我们不用，但无所谓）
4. 等待下载完成（首次较慢，需联网；约 1–2GB）。

### 1.2 确认 SDK 已装好

Android Studio 里：**Settings → Android SDK → SDK Platforms**，勾选 **Android 14 (API 34)**；  
再切到 **SDK Tools** 标签，确认勾选 **Android SDK Build-Tools 34**、**Android SDK Platform-Tools**、**Android SDK Command-line Tools** → 点 Apply。

> JDK 17 会随 Android Studio 自带，无需单独安装。

---

## 第 2 步：打开工程并打包

### 方式 A：图形界面（推荐，最省事）

1. 打开 Android Studio → **File → Open**
2. 选中目录 `CarDanceVisualizer`（**注意：是包含 app 和 settings.gradle.kts 的那一层**）
3. 右下角若提示 Gradle Sync，点 **Sync Now**，等待同步完成（首次需联网下载依赖）
4. 菜单 **Build → Build Bundle(s)/APK(s) → Build APK(s)**
5. 右下角弹出提示 **APK(s) generated successfully**，点 **locate** 直接打开文件夹

**产物路径**：
```
CarDanceVisualizer\app\build\outputs\apk\debug\app-debug.apk
```

### 方式 B：命令行

在本机 PowerShell / Terminal 里：

```bash
cd CarDanceVisualizer
./gradlew assembleDebug          # macOS / Linux
gradlew.bat assembleDebug        # Windows
```

产物同样是 `app\build\outputs\apk\debug\app-debug.apk`

---

## 第 3 步：装到车机

1. **优盘先格式化成 FAT32**（重要，车机对 NTFS 兼容性差；容量建议 ≤ 128GB）
2. 把 `app-debug.apk` 拷进优盘根目录（可改名为 `dance.apk`，方便找）
3. 优盘插入车机 **USB 数据口**（部分车机有口只充电，不读数据 —— 换一个口试）
4. 车机上打开「文件管理 / 文件浏览器」→ 找到优盘里的 APK → 点击安装
5. 若提示「未知来源 / 禁止安装」：进入车机 **设置 → 安全 → 允许安装未知应用**，给「文件管理」授权，再回来安装
6. 装好后打开 App：
   - **点按屏幕** → 切换律动模式（随机 / AI 生成）
   - **长按屏幕** → 选择本地或优盘里的音乐文件（本地播放模式）

---

## 打开工程时可能遇到的问题

| 现象 | 原因 | 解决 |
|---|---|---|
| 卡在 "Gradle Sync" 很久 | 首次下载依赖 | 保持联网，等 5–15 分钟 |
| 报 "SDK location not found" | 没配 SDK 路径 | 在 `CarDanceVisualizer/local.properties` 里加一行：`sdk.dir=C\:\\Users\\lenovo\\AppData\\Local\\Android\\Sdk`（按你的实际路径改） |
| 报 "NDK/Platform not installed" | 缺 API 34 | Settings → Android SDK → 勾 API 34 → Apply |
| 编译时报 "Unsupported class file major version" | JDK 版本不对 | Settings → Build → Gradle JDK 选 **17** |
| 装上车机后显示"未检测到音频" | 该车机未开放混音采集 | 长按屏幕，选一首本地音乐（本地播放模式 100% 同步） |

---

## 关于"优盘插上就能用"的真实情况

程序的安装必须经由车机的**文件管理器 + 手动点击安装**一次；Android 不会优盘一插就自动装应用（这是系统安全限制，所有第三方 App 都一样）。装完一次之后，下次启动直接点图标即可，不需要再插优盘（除非你要换歌）。

---

## 免装环境：用 GitHub Actions 云端打包（推荐）

配置文件已经放好在仓库里：`.github/workflows/build-apk.yml`

### 操作步骤

1. 在 GitHub 新建一个仓库（Public / Private 都可以）。
2. 把 `CarDanceVisualizer` 整个文件夹推上去：

```bash
cd CarDanceVisualizer
git init
git add .
git commit -m "车载律动舞蹈可视化"
git branch -M main
git remote add origin https://github.com/你的账号/你的仓库.git
git push -u origin main
```

3. 打开仓库页面 → 点 **Actions** 标签。
4. 左侧选 **生成律动舞蹈 APK** → 右侧点 **Run workflow**。
5. 等待运行结束（首次约 3–8 分钟）。
6. 在该次运行页面最底部 **Artifacts** 区域，下载 `律动舞蹈-APK`，解压即得 `app-debug.apk`。

### 产物路径

```
app/build/outputs/apk/debug/app-debug.apk
```

> Debug 版 APK 可直接装车机使用（需在车机设置里允许「未知来源」）。
> 以后若要做正式分发版，再补签名配置即可，不用改业务代码。

---

## 如果你不想装 Android Studio

我可以帮你写一份 **GitHub Actions 自动打包配置**：把代码推到 GitHub，云端自动编译，产出一个可直接下载的 APK 链接。适合不想在本地装几个 GB 开发环境的情况。需要的话告诉我。
