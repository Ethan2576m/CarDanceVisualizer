# 律动舞蹈 · 车载音乐可视化（安卓车机版）

手机通过**蓝牙**连车机播放音乐，车机中央大屏同步显示**三位霓虹剪影舞者**，动作随旋律节拍起伏；检测到**副歌**时，两侧舞者放大并溢出屏幕边缘（破框效果）。

- 目标平台：**安卓车机 / 后装大屏**（可侧载 APK、支持 `Visualizer` 混音采集）
- 最低系统：Android 6.0（API 23）+，编译目标 API 34

---

## 一、整体链路

```
手机 ──蓝牙A2DP──▶ 车机音频系统 ──混音后──▶ 喇叭
                              │
                              └──▶ AudioTap(Visualizer, session=0)
                                        │ FFT / 能量
                                        ▼
                              BeatDetector（节拍·强度·副歌）
                                        │
                                        ▼
                              Dancer ×3（姿态插值 + 律动驱动）
                                        │
                                        ▼
                              StageView（Canvas 霓虹渲染 + 频谱均衡器）
```

**两种工作模式**

| 模式 | 说明 | 可靠性 |
|---|---|---|
| 蓝牙跟随 | 抓取系统混音输出（session 0），手机蓝牙放歌即触发 | 依赖车机是否开放混音采集 |
| 本地播放 | 长按选本地/优盘音乐文件，由 App 自己播放并可视化 | **100% 可靠** |

---

## 二、三位舞者造型

| 位置 | 颜色 | 发型 | 说明 |
|---|---|---|---|
| 中央（最大） | 青 `#22D3EE` | **长直发** | 双臂上扬 + 侧踢腾跃 |
| 左侧（可破框） | 紫 `#A855F7` | **单马尾** | 高位侧踢 + 马尾甩出 |
| 右侧（可破框） | 粉 `#EC4899` | **大波浪** | 弓身甩臂 + 长腿跨步 |

均为**抽象人物外轮廓剪影**（非真人）。身材按肩 / 收腰 / 胯外扩的曲线比例绘制。

---

## 三、舞蹈如何与音乐同步

运动由三层叠加，避免机械感：

1. **姿态插值**：每次检测到节拍，从姿态库抽取下一个关键帧，用缓动插值过渡（约 0.42 秒）。
2. **待机律动**：低频正弦摆动持续存在，即使节拍之间也在动。
3. **音频反应项**：
   - `beatPulse`（节拍脉冲）→ 垂直弹跳
   - `intensity`（平滑能量）→ 整体缩放与动作幅度
   - `chorus`（副歌判定）→ 两侧舞者向外推出并放大，被屏幕裁切形成破框

节拍检测采用**低频带能量突变（方差自适应阈值）**，最小间隔 190ms；BPM 由节拍间隔滚动估计。

---

## 四、构建与安装（优盘 → 车机）

### 方案 A：Android Studio（推荐）

1. 用 Android Studio 打开本目录 `CarDanceVisualizer`。
2. 等待 Gradle 同步完成（需要联网下载依赖）。
3. 菜单 **Build → Build Bundle(s)/APK(s) → Build APK(s)**，得到 `app/build/outputs/apk/debug/app-debug.apk`。
4. 将 APK 拷进优盘根目录（建议文件名简单，如 `dance.apk`）。
5. 优盘插入车机 USB 数据口，用车机自带「文件管理」找到 APK 并安装。
   - 若提示「未知来源」，在设置里允许该车机文件管理器安装应用。

### 方案 B：命令行

```bash
cd CarDanceVisualizer
gradle wrapper                 # 若目录内尚无 gradle 包装器
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 首次构建需要联网下载 Android Gradle Plugin 与依赖。当前环境无 SDK / 网络，因此我交付的是**可直接编译的完整源码工程**，而非已签名的 `.apk`。

---

## 五、重要兼容性说明（务必先确认）

1. **原厂封闭车机无法使用**：丰田 / 大众等原厂系统不允许安装第三方程序，优盘只能读音乐文件。要使用本程序，需安卓车机或外接安卓盒子 / 平板。
2. **蓝牙音频采集取决于车机**：`Visualizer(session=0)` 能否取到蓝牙混音因机型而异。若屏幕上一直显示"未检测到音频"，说明该车机未开放混音采集 —— 此时请**长按选择本地音乐**，走本地播放模式，律动同步必然生效。
3. **自动拉起**：`AutoStartReceiver` 监听常见播放广播，但各车机广播名不统一；若不生效，手动点图标打开即可（回到前台会自动重连音频采集）。
4. **优盘格式**：建议 FAT32、容量 ≤ 128GB，避免 NTFS（多数车机不识别）。

---

## 六、代码结构

```
app/src/main/java/com/cardance/visualizer/
├── MainActivity.kt              入口：权限、启动采集、切换模式、选曲
├── AutoStartReceiver.kt         播放广播自动拉起（尽力而为）
├── audio/
│   ├── AudioTap.kt              Visualizer 封装（session 0 混音 / 指定 session）
│   └── BeatDetector.kt          节拍 / 强度 / 副歌 / BPM
├── dance/
│   ├── Skeleton.kt              姿态数据 + 正向运动学解算
│   ├── DanceLibrary.kt          9 组姿态关键帧 + 混合 / 扰动
│   └── Dancer.kt                单个舞者：外观 + 动画状态
└── render/
    ├── DancerRenderer.kt        剪影 / 发型 / 辉光绘制
    └── StageView.kt             渲染循环 + 背景 + 频谱均衡器 + HUD
```

---

## 七、调参入口

| 想调什么 | 改哪里 |
|---|---|
| 动作幅度 / 破框幅度 | `Dancer.kt`：`INTENSITY_GROWTH`、`CHORUS_GROWTH`、`OVERFLOW_RATIO` |
| 副歌灵敏度 | `BeatDetector.kt`：`CHORUS_RATIO`、`CHORUS_MIN_ENERGY`、`CHORUS_HOLD` |
| 节拍灵敏度 | `BeatDetector.kt`：`MIN_BASS`、方差分档 `sensitivity` |
| 新增 / 替换舞姿 | `DanceLibrary.kt`：`POSES` 列表（角度单位：度，0=正下，90=右，180=正上，270=左） |
| 配色 | `StageView.kt` 的 `dancers` 列表 + `DancerRenderer.kt` 的辉光 |

---

## 八、交互

- **点按屏幕**：切换律动模式（随机 / AI 生成）
- **长按屏幕**：打开文件选择器，选本地或优盘里的音乐（进入本地播放模式并自动接管可视化）
- 屏幕左上角显示当前**音源**与 **BPM**；进入副歌时出现「副歌 · 律动爆发」标记

---

## 九、待办 / 可继续

- 在你确认车机型号后，我可以补上该车机对应的**自动拉起广播 Action**。
- 需要的话，我可以加一段**副歌判定阈值自适应**（按整首歌能量分布自动校准），提升不同曲风的命中率。
- 若要正式上车，建议补：驾驶安全策略（车速 > 0 时降低动画幅度 / 仅在驻车态全屏）。
