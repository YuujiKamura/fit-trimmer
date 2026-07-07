# FIT TRIMMER Code Map

このリポジトリを初めて触る人向けの、変更場所を探すための最短ガイドです。

## 全体像

- `shared-core/`: FIT/MP4/HUD/エンコードなど、UI から独立した中核ロジック。
- `composeApp/`: Compose Desktop の GUI、Windows 動画プレビュー、画面状態、バッチ処理。
- `src/`, `index.html`, `hud_designer.html`: 旧/軽量 Web 側。Kotlin デスクトップ機能とは別系統。
- `docs/ARCHITECTURE.md`: 詳細な設計説明。迷ったら先に読む。

## よく触る場所

### UI を変えたい

- 画面全体の構成: `composeApp/src/desktopMain/kotlin/FitTrimmerMainContent.kt`
- 個別コンポーネント:
  - 動画プレビュー: `composeApp/src/desktopMain/kotlin/components/VideoPreviewArea.kt`
  - タイムライン/グラフ: `composeApp/src/desktopMain/kotlin/components/TelemetryTimelineGraph.kt`
  - エンコード進捗: `composeApp/src/desktopMain/kotlin/components/EncodingProgressArea.kt`
  - バッチキュー画面: `composeApp/src/desktopMain/kotlin/BatchQueueDialog.kt`
- 表示文言/翻訳: `composeApp/src/desktopMain/resources/strings*.properties`

### 画面状態やボタン操作を変えたい

- メイン状態: `composeApp/src/desktopMain/kotlin/viewmodel/AppViewModel.kt`
- 起動、CLI/GUI 分岐、更新チェック、バッチ実行: `composeApp/src/desktopMain/kotlin/Main.kt`
- 時刻補正 UI 状態: `composeApp/src/desktopMain/kotlin/TimeOffset.kt`
- GUI の履歴/キャッシュ: `composeApp/src/desktopMain/kotlin/utils/GuiCache.kt`
- バッチキューの永続化: `composeApp/src/desktopMain/kotlin/utils/BatchQueueCache.kt`

### HUD の見た目を変えたい

- HUD 設定データ: `shared-core/src/commonMain/kotlin/fit/HudSettings.kt`
- HUD 描画本体: `shared-core/src/commonMain/kotlin/fit/HudRenderer.kt`
- デスクトップ側のホットリロード接続: `composeApp/src/desktopMain/kotlin/DynamicHud.kt`
- プレビュー側キャンバス実装: `composeApp/src/desktopMain/kotlin/components/VideoPreviewArea.kt`

### エンコード処理を変えたい

- UI からエンコードまでの橋渡し: `composeApp/src/desktopMain/kotlin/HudEncodePipeline.kt`
- FFmpeg/HUD 合成の実処理: `shared-core/src/desktopMain/kotlin/NativeHudEncoder.kt`
- 出力ファイル名: `shared-core/src/desktopMain/kotlin/fit/HudFileNameFormatter.kt`
- ジョブ/一時ディレクトリ/復旧: `shared-core/src/desktopMain/kotlin/fit/CacheJobManager.kt`, `shared-core/src/desktopMain/kotlin/PathResolver.kt`

### FIT/動画メタデータ同期を変えたい

- FIT パーサ/テレメトリ抽出: `shared-core/src/commonMain/kotlin/fit/FitParser.kt`
- MP4 メタデータ解析: `shared-core/src/commonMain/kotlin/mp4/Mp4Parser.kt`
- タイムライン変換: `shared-core/src/commonMain/kotlin/fit/TimelineMapper.kt`
- 速度区間生成: `shared-core/src/commonMain/kotlin/fit/SpeedMapper.kt`
- IMU による同期: `composeApp/src/desktopMain/kotlin/utils/TelemetryAligner.kt`

### 路線名テロップを変えたい

- 路線名検出: `shared-core/src/commonMain/kotlin/fit/GsiRoadDetector.kt`
- 表示文言の組み立て: `shared-core/src/commonMain/kotlin/fit/RoadNameBuilder.kt`
- 国/言語別フォーマット: `shared-core/src/commonMain/kotlin/fit/RoadCaptionFormatter.kt`
- エンコード前の自動検出入口: `composeApp/src/desktopMain/kotlin/Main.kt`

### ナンバープレートぼかしを変えたい

- 検出マネージャ: `composeApp/src/desktopMain/kotlin/utils/PlateDetectionManager.kt`
- ONNX 検出器: `composeApp/src/desktopMain/kotlin/utils/PlateDetector.kt`
- キャッシュデータ構造: `shared-core/src/commonMain/kotlin/fit/PlateCache.kt`
- キャッシュ保存/読込: `shared-core/src/desktopMain/kotlin/fit/PlateCacheManager.kt`
- エンコード前スキャン: `composeApp/src/desktopMain/kotlin/HudEncodePipeline.kt`

### Windows 動画プレビューを変えたい

- Compose 側プレビュー領域: `composeApp/src/desktopMain/kotlin/components/VideoPreviewArea.kt`
- Windows Media Foundation 状態: `composeApp/src/desktopMain/kotlin/io/github/kdroidfilter/composemediaplayer/windows/WindowsVideoPlayerState.kt`
- ネイティブサーフェス: `composeApp/src/desktopMain/kotlin/io/github/kdroidfilter/composemediaplayer/windows/WindowsVideoPlayerSurface.kt`
- DLL: `composeApp/src/desktopMain/resources/win32-*/NativeVideoPlayer.dll`

## テストと確認

- 全体の Kotlin コンパイル確認: `.\gradlew.bat :composeApp:compileKotlinDesktop`
- デスクトップ UI/アプリ側テスト: `.\gradlew.bat :composeApp:desktopTest`
- core 側テスト: `.\gradlew.bat :shared-core:desktopTest`
- JS parser テスト: `npm test`
- Gradle タスク一覧: `.\gradlew.bat tasks`

## 注意点

- 直接 `File(...)` I/O は制限されている。許可ファイル以外で追加すると `verifyNoStrayFileIO` に落ちるため、キャッシュ/一時ファイルは既存の `PathResolver`, `CacheRegistry`, `GuiCache` 系に寄せる。
- `Main.kt`, `FitTrimmerMainContent.kt`, `AppViewModel.kt` は巨大なので、変更前に該当関数名で `rg` して局所的に読む。
- `shared-core/commonMain` は Wasm も通る前提。JVM/Windows 専用 API は `desktopMain` か `composeApp` 側に置く。
- Windows 専用の動画プレビュー/DLL 周辺は壊れやすい。変更したら GUI 起動かスモークテストで確認する。
