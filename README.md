# 愤怒的小鸟 · Android 复刻版

用原生 Android（Kotlin + Canvas + JBox2D）复刻的《愤怒的小鸟》。无 XML 布局、无图片资源、无音频资源，全部界面与音效由代码生成。

- 最低兼容：Android 11（API 30）
- 目标版本：Android 14（API 34）

## 功能特性

### 玩法
- 13 个关卡：12 个主线关 + 1 个隐藏关「黄金岛」
- 弹弓拖拽瞄准、最大拉伸限制、抛物线预测点
- 黄鸟：飞行中点击冲刺加速
- 黑鸟：点击或落地自动爆炸
- 材质系统：木头 / 冰块 / 石头 / TNT，各自拥有不同血量与脆弱度
- TNT 连锁爆炸、方块破碎、猪被消灭
- 每关 1 颗炸弹道具（隐藏关 2 颗）：点击 HUD 炸弹按钮后，再点场景任意位置投弹
- 星级评分（按关卡分数阈值）、剩余小鸟奖励、最高分记录
- 通关自动解锁下一关；集齐 3 枚金蛋解锁隐藏关

### 金蛋
第 1、3、5、8、12 关各藏 1 枚金蛋，小鸟碰到即收集（+3000 分），进度写入本地存档。

### 表现
- 60 FPS 独立渲染线程，Canvas 矢量绘制
- 猪的受伤动画：受击闪白/抖动、血量降低后瞳孔收缩、濒死时 X 眼与头顶旋转小星星
- 粒子、得分飘字、震屏、闪白
- 音效全部由代码合成（AudioTrack 静态 PCM）：发射、木/冰/石破碎、猪叫、爆炸、金蛋、胜负
- 震动反馈（VibrationEffect），带 55 ms 节流

### 其它页面
- 标题页、选关页（星级 / 锁定 / 金蛋进度）
- 本地排行榜：总分、总星数、各关最高分降序
- 设置页：查看当前进度、二次确认后清除所有存档
- 暂停 / 继续 / 重玩 / 选关 / 下一关；切后台自动暂停

## 操作说明

| 操作 | 说明 |
|---|---|
| 在弹弓上拖拽 | 调整角度与力度 |
| 松手 | 发射小鸟 |
| 飞行中点击屏幕 | 触发黄鸟加速 / 黑鸟爆炸 |
| 左下角炸弹按钮 | 进入投弹模式，再点场景目标位置投弹 |
| 左上角暂停按钮 | 暂停 |
| 系统返回键 | 暂停 / 返回上一层 |

## 技术栈

| 项目 | 版本 / 说明 |
|---|---|
| 语言 | Kotlin 1.9.24 |
| 构建 | AGP 8.5.2 / Gradle 8.7 |
| JDK | 17 |
| compileSdk / targetSdk | 34 |
| minSdk | 30（Android 11） |
| 物理引擎 | JBox2D `org.jbox2d:jbox2d-library:2.2.1.1` |
| 渲染 | SurfaceView + Canvas + 独立渲染线程 |
| 输入 | 触摸事件经并发队列投递到渲染线程 |
| 存档 | SharedPreferences |

## 项目结构

```
app/src/main/java/com/redbird/angrybirds/
├── MainActivity.kt   入口：沉浸式全屏、生命周期与返回键转发
├── GameView.kt       SurfaceView 宿主：渲染线程、触摸队列、场景路由
├── Renderer.kt       全部 Canvas 绘制（场景/HUD/覆盖层/标题/选关/排行榜/设置）
├── Game.kt           物理世界、弹弓发射、伤害结算、回合状态机、计分
├── Entities.kt       实体模型（鸟/猪/方块/金蛋）、粒子、飘字
├── Levels.kt         13 关关卡数据与关卡构建器
├── Audio.kt          代码合成音效与震动反馈
├── Progress.kt       星级 / 最高分 / 解锁 / 金蛋存档
└── Ui.kt             按钮与格子的布局矩形
```

### 坐标系约定

- 物理世界：1 单位 = 1 米，y 轴向上，地面 `y = 0`
- 弹弓锚点：`(4.2, 2.35)`，最大拉伸 1.35 m，发射速度 = (锚点 − 拖拽点) × 17
- 像素/米 `ppm = min(viewWidth / 22, viewHeight / 11.5)`

### 关卡扩展

在 [Levels.kt](app/src/main/java/com/redbird/angrybirds/Levels.kt) 中使用构建器：

```kotlin
private fun levelX() = Builder("关卡名", 宽度, 二星分, 三星分).apply {
    col(19.0f, 0f, 2.4f)              // 竖直柱子：x, 底面高度, 高度
    beam(20.2f, 2.4f, 2.8f)           // 横梁：中心 x, 底面高度, 宽度
    box(17.4f, 0f, 0.7f, 0.7f, Material.TNT)
    pig(20.2f)                        // 猪：x, 底面高度
    egg(23.6f, 2.2f)                  // 金蛋（可选）
    birds(BirdType.RED, BirdType.YELLOW, BirdType.BLACK)
}.build()
```

## 构建与运行

使用 Android Studio 打开项目即可，或在本机安装 Gradle 8.7 后执行：

```bash
gradle assembleDebug
```

> 说明：仓库未包含 `gradle-wrapper.jar`（按要求未下载额外文件）。若使用 `gradlew`，请先用 Android Studio 或本机 Gradle 生成 wrapper。

JBox2D 依赖从 Maven Central 自动拉取，首次构建需要网络。

## 已知限制

- 项目尚未经过完整编译与真机运行验证
- 无背景音乐，音效为代码合成的简短音色
- 震动效果需要真机，模拟器通常无马达
- 排行榜为本地榜单，无网络与账号系统
- 美术为 Canvas 矢量绘制，未使用贴图资源
