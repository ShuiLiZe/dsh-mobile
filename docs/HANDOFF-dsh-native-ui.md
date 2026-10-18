# 转交文档：dsh-mobile DSH 原生风格改版

更新：2026-10-18 · 仓库 `ShuiLiZe/dsh-mobile`（fork 自 `saya-ch/dsh-mobile`）· 工作分支 `feat/dsh-native-theme`

## 1. 目标
- dsh-mobile 自己绘制的界面（网页端外壳/设置/配对页 + Android APK 原生页）统一成 DSH 桌面/网页版的原生风格。
- **硬性要求：不覆盖 DSH 的主题。** 桌面端安装/切换的主题，手机连接后必须同步生效；深浅色跟随系统/DSH。→ 一律读取 DSH 主题变量 `--dsw-alias-*`，禁止新增写死的颜色。
- 图标：去掉 APK 现有的六种彩色鲸鱼启动图标，统一换成 DSH 官方标志；应用内小图标改为 DSH 同款线性风格。

## 2. 工作规则
- 只在 feature 分支开发，经 PR 合入 `main`；不直接改 `main`，不碰上游仓库。
- 用户要求**小步勤提交、勤推送**（feature 分支和 PR 可直接推）；合并到 `main` 前先问用户。
- 提交身份：`ShuiLiZe <58876253@qq.com>`。
- 用户偏好中文沟通。

## 3. 已完成
- **上游同步机器人**：PR #1（分支 `chore/upstream-sync`，最新提交 `a7052f0`），`.github/workflows/sync-upstream.yml`
  - 每天北京时间 04:00 把 `saya-ch/dsh-mobile@main` 以 merge（`--no-ff`）合入 `main`；
  - 干净合并且 `npm run typecheck` + `npm test` 通过才推送；
  - 有冲突 / 测试失败 / 上游改了工作流文件导致无权推送 → 不动 `main`，推 `upstream-sync` 分支并开 PR 列出冲突文件；
  - 从不 force-push、从不自动解决冲突。
  - 待办：PR #1 标题仍是旧的“no auto-merge”，需改标题；合并后在仓库 Settings → Actions 开启读写权限 + 允许 Actions 创建 PR。
- **DS Bot 兼容**（另一仓库 `ShuiLiZe/DeepSeek-Bot`，PR #5，分支 `feat/dsh-mobile-compat`）：弹层层级、手机设置页、安全区、Android 返回键。未合并，未做真机测试。

## 4. 现状分析（改版依据）
### 网页端
- `src/client.ts`（`CONTROL_STYLES`，约 4394–4430 行）、`mobile-layout.ts`、`native-mobile.ts`、`client-module-ui.ts` **大部分已经使用** `var(--dsw-alias-*, 回退色)`，因此已能跟随 DSH 主题。
- 需要修的残留写死项：
  - 字体：约 50 处 `font: … system-ui` → 改为 `inherit`（继承 DSH 字体）。
  - 写死的蓝：`#6f96db`、`#9fb9e8`、`#1d4ed8`、`#1e40af`、焦点框 `rgb(37 99 235/28%)` → `--dsw-alias-brand-primary` / `--dsw-alias-button-primary-fill` / `--dsw-alias-button-primary-hover` / `--dsw-alias-link`。
  - 提示色 `#d6a83f`、成功徽标 `#087454`/`#fff` → `--dsw-alias-state-warn-*` / `--dsw-alias-state-success-*`。
  - toast（`client.ts` ~3967 行 `#1f2937`/`white`）→ `--dsw-alias-toast-bg` / `--dsw-alias-toast-label`。
  - 阴影与遮罩 `rgb(15 23 42 / x%)`（mobile-layout、native-mobile、client-module-ui）→ `--dsw-alias-bg-mask-1` / `--dsw-alias-bg-overlay`，阴影保留但降低强度以适配深色主题。
  - `src/mobile-boot-feedback.ts`：启动页自带一套浅/深色色板（`#fff/#111827/#1e293b…`）。DSH 变量可能尚未加载，保留回退色，但优先读 `--dsw-alias-*`。
- DSH 可用变量（已从 DSH 包中提取）：`label-primary/secondary/tertiary`、`bg-base`、`bg-layer-1/2/3`、`border-l1…l4`、`brand-primary`、`link`、`button-primary-fill/hover`、`interactive-bg-hover/active`、`state-success/warn/error-*`、`toast-bg/label`、`tooltip-bg`、`bg-mask-1`、`bg-overlay`。

### Android APK（`apps/mobile/android/app/src/main/`）
- 原生界面用 Kotlin 代码构建：`MainActivity.kt`、`ScanActivity.kt`；另有 `StatusBarChrome.kt`、`SafeAreaPadding.kt` 等。
- 颜色：`res/values/colors.xml` 为通用 Material 色（accent `#2563EB` 等），有 `values-night`。
- 启动图标：`res/mipmap-anydpi-v26/ic_launcher_whale{,_black,_dark,_mono,_teal,_white}.xml` 六种，可能通过 activity-alias 切换。
- 小图标：`res/drawable/ic_*.xml`（chevron、more、refresh、settings、scan zoom、通知）。

## 5. 建议实施步骤（每步一个提交并推送）
1. 网页端：替换第 4 节列出的写死颜色和字体 → `npm run typecheck && npm test` → 提交推送。
2. 启动页 `mobile-boot-feedback.ts` 读取 DSH 变量。
3. **主题同步到原生**：WebView 加载 DSH 后，用 JS 读取 `getComputedStyle(document.documentElement)` 中的关键 `--dsw-alias-*`，经现有原生桥传给 Android，存入 SharedPreferences；原生页（设备列表、扫码、配对、设置）与状态栏/导航栏按缓存主题着色，首次无缓存时用 DSH 默认浅/深色。
4. Android `colors.xml` / `values-night` 改为 DSH 默认色（作为无缓存时的回退），统一圆角、间距、字号。
5. 启动图标：用 DSH 品牌包（`@deepseek-ai/dsh-brand` / `dsh-client-ui-brand-official`）里的标志生成 adaptive icon（前景 + 纯色背景 + monochrome），删除鲸鱼变体及切换逻辑（同步清理 Manifest 中的 activity-alias 和相关设置项）。
6. 应用内 `drawable/ic_*` 换成与 DSH 一致的线性图标。
7. 开 PR；在 CI 中构建 **debug APK** 作为 artifact 给用户测试。
   - 注意：发布版签名用的是上游密钥（`ANDROID_KEYSTORE_*` secrets）。自建 APK 签名不同，安装前必须卸载原版并重新配对；如需正式版，用户要在 fork 中配置自己的 keystore secrets。

## 6. 验证清单
- DSH 默认浅色、深色，以及安装一个第三方主题后：桌面端外观不变；手机连接后外壳、设置、弹层颜色与桌面一致。
- 390×844 触屏：抽屉、设置、诊断、扫码页可用，安全区正常。
- Android：首次启动（无缓存）与连接后再次启动（有缓存）原生页颜色；系统深色模式切换；启动图标在各启动器正常。
- 上游同步工作流手动触发一次（workflow_dispatch）确认逻辑。
