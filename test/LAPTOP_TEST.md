# 在笔记本上跑起来 —— 实机与模拟器实测指南

> 目标：不装车也能验证「舞蹈动作 / 节拍同步 / 副歌破框」是否正常。
> 结论先说：**没有安卓手机也能测** —— 新版内置「演示模式」，双击屏幕即用合成节拍驱动舞者，完全不依赖音频采集。

---

## 0. 交互速查（新版）

| 手势 | 作用 |
|---|---|
| **单击** | 切换律动模式（随机 / AI 生成） |
| **双击** | 开关**演示模式**（合成节拍，无需音频） |
| **长按** | 选择本地 / 优盘里的音乐文件 |

> 演示模式下 HUD 左上角显示「演示模式 · 合成节拍」，右下角提示「双击关闭演示 · 长按选择音乐」。

---

## 1. 最快路径：装 APK → 双击两下就看到舞者

1. 下载 APK（见下节），装到任意安卓环境（模拟器/真机）
2. 打开 App → **双击屏幕** → 演示模式开启，舞者立刻开始跳
3. 观察 16 秒一个循环：
   - 0–4s 弱 → 8–12s **副歌**（HUD 亮起「副歌 · 律动爆发」标签，左右舞者冲出屏幕边缘）
4. 双击关闭 → 回到音频跟随模式

这条路径**不需要任何音频、不需要权限、不需要推歌**，是验证画面最省事的方式。

---

## 2. 拿到 APK

1. 打开仓库 → **Actions** → 点跑通的那次 **Update build-apk.yml**（绿勾 ✅）
2. 页面拉到最底部 **Artifacts** → 下载 **`car-dance-apk`**
3. 解压得到 `app-debug.apk`（约 2.9 MB），放到 `CarDanceVisualizer/` 目录下

```
CarDanceVisualizer/
├── app-debug.apk        ← 放这里
└── test/
    ├── test_track.wav
    ├── deploy_to_device.ps1
    └── make_test_track.py
```

---

## 1. 真机路线（推荐，最接近车机）

### 准备
- 一台 Android 手机（Android 6.0 以上，minSdk 23）
- 手机开 **开发者选项 → USB 调试**
- 电脑上要有 `adb`（装 Android Studio 会自带：`platform-tools`）

### 三种装法，任选

**A. 无线调试（不用插线，最省事）**
```powershell
adb pair 手机上显示的IP:端口        # 手机：开发者选项 → 无线调试 → 配对
adb connect 手机上显示的IP:端口
adb devices                          # 确认状态为 device
```

**B. 数据线**
```powershell
adb devices                          # 确认出现设备号且状态为 device
```

**C. 纯手工（完全不碰 adb）**
把 `app-debug.apk` 传到手机（微信文件传输助手 / QQ / 数据线拷贝）→ 点安装 → 允许「未知来源」→ 打开 App。
然后**长按屏幕**选一首手机里的歌即可。

### 自动化部署（推荐）
```powershell
cd CarDanceVisualizer\test
powershell -ExecutionPolicy Bypass -File .\deploy_to_device.ps1
```
脚本会：装 APK → 推送 `test_track.wav` 到 `/sdcard/Music/` → 授予录音权限 → 拉起 App。

### 真机上怎么验
1. App 打开后，**长按屏幕** → 文档 → 内部存储 → Music → `test_track.wav`（或任意歌曲）
2. 播放后看 HUD：
   - 左下角显示 **BPM 数字**（测试音轨是 120 BPM，偏差 ±2 正常）
   - 出现 **「副歌 · 律动爆发」** 标签 → 副歌检测生效
   - 左右两个舞者**冲出屏幕边界** → 破框动作生效
3. 想测蓝牙：手机连蓝牙音箱/耳机放歌，然后**点按屏幕**（不是长按）切回跟随模式 →
   - 音箱放的是手机音乐 → 理论上走 session=0 混音采集
   - 音箱放的是**别的 App 的音乐**（比如 QQ 音乐）→ 同样应该被抓到
   - 若 HUD 提示「未检测到音频」→ 说明这台机器/ROM 不开放混音采集，**用长按本地播放即可，功能完全正常**

---

## 2. 模拟器路线（Android Studio AVD）

### 建模拟器
1. Android Studio → **Device Manager** → **Create Device** → 选 **Pixel 6**
2. System Image 选 **API 34**（Android 14）→ 建议勾 **Google APIs x86_64**
3. 启动模拟器，等完全开机（Boot completed）

### 部署
```powershell
cd CarDanceVisualizer\test
powershell -ExecutionPolicy Bypass -File .\deploy_to_device.ps1
```

### 模拟器上的三条硬限制（提前知道，免得误判）

| 限制 | 表现 | 绕法 |
|---|---|---|
| **混音采集（session=0）基本无效** | 蓝牙模式永远「未检测到音频」 | 用**双击 → 演示模式**，或**长按 → 本地音乐** |
| 文件选择器里看不到推过去的歌 | 选文件时列表是空的 | 脚本已推到 `/sdcard/Music`，在 Documents → 内部存储 → Music 里找 |
| 竖屏显示 | App 锁定横屏，屏幕上下留黑边 | 模拟器按 `Ctrl+F11`/`F11` 旋转，或 Device Manager 里把 skin 设为横屏 |

### 第三方模拟器（雷电 / MuMu / 夜神）也能用

不想装 Android Studio（几个 GB）的话，Windows 上的第三方模拟器更轻：

| 模拟器 | adb 端口 | 备注 |
|---|---|---|
| MuMu 模拟器 12 | 7555 | 需在 设置 → 其他 → 开启 adb 调试 |
| 雷电模拟器 | 5555 | 设置 → 其他 → 打开 ADB 调试 |
| 夜神 / BlueStacks | 62001 / 21503 | |

`deploy_to_device.ps1` 会**自动尝试连接 7555 / 5555 / 5554 / 62001 / 21503 / 62025** 这几个常见端口，不用手动指定。

### 模拟器性能提示
舞者用了霓虹辉光（shadow layer）+ 60fps 重绘，模拟器上可能掉帧：
- Device Manager → 模拟器配置 → **Graphics: Hardware**（别选 Software）
- 分辨率别开太高，1080p 足够

---

## 3. 测试音轨说明

`test_track.wav`（32 秒 / 120 BPM / 44.1kHz 立体声）由 `make_test_track.py` 合成，结构专门对齐 App 的判定逻辑：

| 时间 | 段落 | 能量 | 期望表现 |
|---|---|---|---|
| 0–8s | intro | 弱 | 舞者小幅摆动，无副歌标签 |
| 8–16s | verse | 中 | 节拍稳定跟上，BPM 显示 120 |
| 16–24s | **chorus** | 强（约为 verse 的 2.8 倍） | **「副歌 · 律动爆发」标签亮起**，左右舞者破框 |
| 24–32s | break | 回落 | 动作收敛，标签消失 |

想换曲子重新生成：
```bash
python make_test_track.py my_track.wav
```

---

## 4. 常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| `adb: no devices/emulator found` | 模拟器没开完 / 手机没授权 | 等开机完成；手机上点「允许 USB 调试」；第三方模拟器开 adb 调试 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 设备上已有不同签名的同名应用 | 先 `adb uninstall com.cardance.visualizer` 再装 |
| App 打开一片黑，没有舞者 | 还没收到音频 | **双击屏幕开演示模式**，或长按选本地音乐 |
| 双击没反应 | 装的是旧版 APK | 重新下载最新制品（旧版没有演示模式） |
| BPM 数字不跳 | 混音不可用 | 双击开演示模式，或长按改本地播放 |
| 副歌标签一直不亮 | 音乐能量太均匀 | 用 `test_track.wav` 测；演示模式下每 16 秒必亮一次 |
| 模拟器里文件选择器是空的 | 推过去的路径不对 | 用 `/sdcard/Music/test_track.wav`，从「内部存储」进 |
| 舞者卡顿 | 模拟器无 GPU 加速 | Graphics 改 Hardware，模拟器内存调到 2GB+ |

---

## 5. 车机部署（最后一步）

验证通过后再上车：

1. 优盘**格式化成 FAT32**（车机对 NTFS 兼容性差，容量建议 ≤ 128GB）
2. `app-debug.apk` 拷到优盘根目录（可改名 `dance.apk` 方便找）
3. 插入车机 **USB 数据口**（部分车机只有充电口，换一个试）
4. 车机文件管理器 → 点 APK → 安装 → 允许「未知来源」
5. 打开 App：**点按**切律动模式，**长按**选本地/优盘里的歌

> Android 不会「插上优盘就自动装应用」，这是系统安全限制，所有第三方 App 都一样。装一次即可，以后直接点图标。
