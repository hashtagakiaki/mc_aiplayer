# PLAN

Goal: After this change, Bot は有限目標の空回りを機械的に検知し、失敗時には観測した障害に応じて資源・道具・場所などの前提条件を整え、元の目標に戻って達成を試みる。同じ失敗を無制限に繰り返さず、復旧不能なら未達条件を示して終了する。

Acceptance criteria:
- 同じ場所の往復、進捗表示の増減、無関係な所持品の増減、同じ手順の再生成で停滞判定や復旧回数がリセットされない。
- 採掘途中、障害物を迂回中、精錬中、成長待ちを、それぞれの実際の進展・待機条件に基づいて扱う。進捗表示だけを根拠に採掘を中断しない。
- 素材不足で元の計画が進まない場合、観測可能な状況を見直し、必要なら補給・移動・別の入手方法を実行し、その後元の目標を再評価する。
- 原目標、達成条件、建築位置等の文脈、復旧予算を保持し、「つるはしを用意した」だけで「ダイヤを入手した」と報告しない。
- 同一失敗・同一条件で同一行動を再送したLLM応答をMod側で拒否する。失敗理由文字列に座標や回数が付いても反復を見逃さない。
- 正常終了、復旧中、上限到達による終了を区別する。取消・置換・一時停止、新しいユーザー指示に古いモデル応答が割り込まない。
- strict_survival の権限制限、実行中の物理的後片付け、復旧不能なチェックポイントの停止を保持する。

Constraints:
- ユーザーの問題意識: 「現状の課題は停滞が検知できないことがあることと、目標失敗時に、資源の獲得などを再計画できないこと」。
- 設計を複雑にしすぎない。特定タスク限定の救済にせず、判定の枠組みは共有し、判定の材料は各タスクが提供する。
- 停滞/失敗/達成の判定と実行権限はModが持つ。LLMは必要なときに次の手段を選ぶ。
- 調整値は名前付き定数または既存設定に置く。閾値を本書で実測済みと扱わない。
- 今回は設計のみ。稼働サーバー、設定、JAR、ワールドを変更しない。

Out of scope:
- GoalPlanner、経路探索、既存採掘処理の全面置換。汎用AIプランナー、巨大な原因分類体系、無制限の再帰サブ目標。
- 権限緩和、hidden-block scan、テレポート、時間延長だけの無限再試行。
- あらゆる自然地形での達成保証、全タスクの同時全面改修、新しいWeb UI。
- モデル変更、外部依存の追加、デプロイ。

Context:
- 調査時HEAD: `4d33e0c8`。調査開始時 `git status --short` は空。実際のoriginは `git@github.com:hashtagakiaki/mc_aiplayer.git`、upstreamは第三者。AGENTS.mdのorigin記述は古い。第三者へpushしない。
- `src/main/java/io/github/zoyluo/aibot/task/StuckWatcher.java:33` は有限タスクの監視窓を持つが、`:35` で `isWaiting()` 中に記録を削除する。`:86` のSampleは座標変更、progressの増加でも減少でも、所持品総数の変化でも監視をリセットする。タスクインスタンスの交換でもリセットする。
- `src/main/java/io/github/zoyluo/aibot/task/Task.java:26` の `progress()` は表示用と監視用を兼ねている。例えば `task/MoveTask.java:58` は経過時間から進捗を作り、`:66` では掘削中をwaitingにする。この値を単純に単調増加監視へ変えるだけでは不足する。
- `goal/GoalExecutor.java:3366` のreplan判定は完了step数・目標在庫を進捗とし、HUNT以外は8ブロックの横移動や下潜も進捗とする。`:3898` の予算は再計画した時点で評価するため、終わらないタスク自身を検知する監視の代替ではない。
- `goal/GoalPlanner.java:1448` のensureItemは不足品からレシピを再帰展開し、`:1513` 以降で基礎資源の採集を計画する。**資源獲得の再計画が全くないわけではない**。現在地や既存の入手手順で解けない状況からの方針転換が不足している。
- `goal/GoalExecutor.java:3905` のfresh planは決定的なGoalPlanner呼び出し。LLMへの問い合わせではない。`goal/GoalPlanner.java:169` の地上復帰は既存の限定された文脈復旧として再利用可能。
- `brain/BrainCoordinator.java:270` は実行中の目標があると通常のLLM起動を抑制する。`:276` と `:445` 付近ではterminal stuck結果のLLMへの再問い合わせも抑制する。これは以前の再生成ループ防止策なので、単純に削除すると再発し得る。
- `brain/BrainCoordinator.java:528` は既に失敗理由とPerceptionSnapshotをLLMに渡せる。ただし「同じ方法を再試行しない」は主に自然言語の指示である。`task/TaskManager.java:401` の失敗回数は名前と理由文字列の完全一致で数え、`:379` 付近の中間タスク成功で消える。
- `goal/GoalExecutor.java:227` と `:246` は前提サブ目標への変更を拒否し、他の目標は通常キューへ入れる。復旧で必要な補給を普通の新目標としてsubmitすると、この規則に衝突する。
- `goal/GoalExecutor.java:5416` 以降のfinishActiveは結果を確定し、原目標保護を解除し、場合により次のキューへ進む。復旧はここへ到達する前の独立した実行状態として扱う必要がある。
- `goal/GoalExecutor.java:3790` 以降には未解決の帰還義務・不正checkpoint等の終端条件がある。これらを資源不足と同じgeneric recoveryへ流してはいけない。
- `task/EpisodeMemory.java:42` の目的別探索履歴と除外情報、`brain/DecisionSession.java:30` のepoch/lease、既存Mission checkpointを優先して再利用する。
- `perception/PerceptionCollector.java:54` はcapability decisionを記録してから可視範囲を収集する。DENIED_STRICT_SURVIVAL単体はタスク失敗や停滞の証明にならない。見つからない資源は「観察範囲では未発見」であり世界に存在しない証明ではない。
- README/docsのテスト件数やデプロイ状況はコード・会話と不整合があり、現在の実行結果の証拠には使わない。

Experiments and open questions:
- 今回はソース調査とGitの読み取りのみ。実際の停滞を再現したとは主張しない。実行テストや機能コードの変更は行っていない。
- 重要な未確定事項は、既存タスクのどの実績値を共通監視に出せるか、復旧のための既存操作が十分か、どの地点でcheckpointを保持したまま復旧へ入れるか。Wave 1の隔離実験で決める。
- 復旧に必要な探索操作が既存のGather/Move/地上復帰だけで表現できなければ、その不足した操作を一つ追加する。プロンプト変更だけで未実装の行動ができるとは扱わない。
- 監視窓・待機期限・LLM復旧回数は既存値を起点に比較する。正常な遅い採掘/成長待ちとの区別を実測してから確定する。

Approach:
1. **進捗の根拠を表示値から分ける。** 各タスクが既に持つ「実際に壊した対象」「受け取った目的物」「検証済みの工程」「未探索区域を初めて調べた」といった実績を小さな共通形式で返す。表示progress、位置変更、腕振り、時間経過をそのまま実績としない。全タスクに同じ判定枠を使い、個別に複雑な監視器を増やさない。
2. **タスク内と原目標全体の反復を捉える。** 既存StuckWatcherはタスク内の無進展を扱う。原目標側は実績の到達点と試した手段を保持し、同じタスクの再生成・同じ工程のやり直しでは改善と認めない。最終成果だけを判定すると道具作成や探索を誤停止するので、初めて満たした前提条件や有益な探索も認める。ただし探索継続には全体上限を置く。
3. **待機を具体的に扱う。** 有限目標では `isWaiting=true` を無期限の免除にせず、何を待ち、何が観測され、いつ再確認するかを持つ。精錬と作物成長に同じ短い期限を押し付けない。通常採掘の連続破壊進捗は同じブロックについて最高到達点を見る等、振り直しで監視を更新しない。既存のtask-owned watchdogが保証するものは二重実装しない。ユーザーの明示した常時作業とpauseは別扱い。
4. **失敗のまとまった情報を作る。** 原目標と未達条件、失敗した操作/対象、失敗理由、実績の前後、最新所持品と観測、既に試した手段、残り予算を一緒に渡す。失敗文字列を全面置換せず、復旧境界で小さな分類と元の理由を保持する。権限上実行不可、素材不足、未発見、到達不可、不明、実行状態の破損を混同しない。
5. **一つの実行主体のまま復旧へ移る。** GoalExecutorが元のMissionを保持し、通常実行から「復旧判断待ち」へ移る。既存の決定的replanで前提補充が可能ならそれを使い、同条件の反復や未解決ではBrainCoordinatorへ復旧専用の問い合わせを一度発行する。LLMは次の補給・移動・別の入手手段を一つ提案し、Modが検証して実行する。通常チャットの自由なtool dispatchと同時実行しない。
6. **復旧は一段だけ。** 既存のtyped Goal/Taskを使った補助手順を原Missionの復旧として実行し、終わったら現在状態で原目標を再計画する。補助手順の失敗は同じ復旧ループへ戻し、子の子のMissionを作らない。新規ユーザー目標のキューとも分ける。元の目標をただの会話履歴に任せず、Modが保持する。
7. **機械的な反復制限を付ける。** 同じ障害・対象・関係する前提条件で失敗済みの同じ手段は実行前に拒否する。理由の文言変更、座標の小さな揺れ、無関係なアイテム増減を条件改善としない。反対に必要な道具を得た後の同じ採掘は許可する。近傍の有限な履歴と原目標ごとの総予算を持ち、A→B→Aや再起動で回数を洗い流さない。大きい努力とは、別の未探索区域へ行く・前提を整えるなど実際に変わる行動を指す。
8. **結果と実行権限を保持する。** 原目標のpostconditionでのみ完了を確定。復旧中は次の通常キューへ進まない。取消/置換/死亡等は既存leaseを無効化し、安全処理を優先する。再起動時は原Missionと予算を復元し、古いLLM応答を再利用せず、最新観測から必要な判断だけをやり直す。

Failure modes checked:
- position/progressの揺れで無限更新 → StuckWatcherの比較式を確認。単なる変化と有益な実績を分ける。
- 全目標に最終成果だけの短い監視窓 → MoveTaskの時間式、FarmTaskの成熟待ち、GoalPlannerの前提連鎖を確認。実績/待機を分け、正常経路の負の対照を要求する。
- LLM再開だけで同じGoalを作り直す → terminal-stuck抑制の二つの入口と、TaskManagerの失敗カウンタ消去を確認。予算・反復判定をMissionに持つ。
- 補給が拒否/キュー化される → submitの前提保護を確認。通常submit規則を緩めず、原Missionの復旧実行として接続する。
- 復旧中に次の目標や古い応答が割り込む → finishActiveとDecisionSessionを確認。終端前の状態とleaseを利用する。
- 地上復帰・採掘の物理状態を捨てる → GoalExecutorの帰還/サービス/checkpoint終端条件を確認。安全に解放できない状態は勝手に再計画しない。
- 未発見を不存在と扱う、DENIEDログ量を失敗判定にする → PerceptionCollectorのcapability問い合わせと可視フィルタを確認。障害の結果と観測制約を分ける。

Full verification:
- 実装後: `./gradlew test`、`./gradlew runGameTest`、`./gradlew build`、`git diff --check`。
- 永続化変更後: `bash scripts/persistence_restart_test.sh`。既存probeに加えて復旧待ち/復旧操作中の再起動を対象にする。
- 隔離GameTestの必須ケース: 往復移動、採掘の途中中断反復、進捗値往復、関係ない在庫増減、A→B→A、正当な長時間採掘/待機、資源補充→原目標再開、同じ復旧提案拒否、予算到達、取消後の遅延LLM応答、再起動時の予算維持。
- LLMの境界はまず固定応答で検証し、モデルの気まぐれと実行系の不具合を分ける。実モデルで「手段が改善するか」の確認は、実装後の隔離環境で別途行い、固定応答テストだけでその効果を主張しない。

## Wave 1

- [x] Task 1: 既存監視と復旧の接続を隔離ワールドで確認する
  Writes:
  - `src/gametest/java/io/github/zoyluo/aibot/task/RecoveryDesignProbeGameTests.java`（一時実験、結果記録後に削除または有効な回帰チェックへ移行）
  - `src/gametest/resources/fabric.mod.json`（上記probeの一時登録）
  - `PLAN.md`（観測結果と次Waveの具体化）
  Reads:
  - `src/main/java/io/github/zoyluo/aibot/task/StuckWatcher.java`
  - `src/main/java/io/github/zoyluo/aibot/task/Task.java`
  - `src/main/java/io/github/zoyluo/aibot/task/TaskManager.java`
  - `src/main/java/io/github/zoyluo/aibot/task/BotTickCoordinator.java`
  - `src/main/java/io/github/zoyluo/aibot/task/MoveTask.java`
  - `src/main/java/io/github/zoyluo/aibot/task/GatherQuotaTask.java`
  - `src/main/java/io/github/zoyluo/aibot/task/OreDigTask.java`
  - `src/main/java/io/github/zoyluo/aibot/task/FarmTask.java`
  - `src/main/java/io/github/zoyluo/aibot/task/SmeltTask.java`
  - `src/main/java/io/github/zoyluo/aibot/goal/GoalExecutor.java`
  - `src/main/java/io/github/zoyluo/aibot/goal/GoalPlanner.java`
  - `src/main/java/io/github/zoyluo/aibot/brain/BrainCoordinator.java`
  - `src/main/java/io/github/zoyluo/aibot/brain/DecisionSession.java`
  - `src/main/java/io/github/zoyluo/aibot/brain/ToolRegistry.java`
  - `src/gametest/java/io/github/zoyluo/aibot/goal/GoalPlannerMiningGameTests.java`
  Change:
  - production/mainコードを変えず、BotとTaskの小さいfixtureで停滞監視窓を越える静止/往復/進捗値反復/isWaitingを実行して現挙動を記録する。
  - 素材が不足したGoalのfresh planと補充後のfresh planを比較し、既存の資源依存展開を再利用できる範囲を特定する。
  - 正常な採掘/移動/待機の既存実績と期限を列挙し、共通監視が必要な最小情報を選ぶ。表示progressを流用できないタスクを明記する。
  - 原Mission保持、補助手順のsubmit規則、キュー遷移、checkpoint保存の接続点をfixtureで確認する。安全上復旧対象にできない終端を分ける。
  - 結果をPLANへ追記し、以下のDeferred workをWritesが確定した小タスクへ展開する。一時probeと登録は戻し、結果を記録する。
  Verify:
  - `./gradlew runGameTest`
  - `git diff --check`
  - `git status --short`
  Expected:
  - 現コードの静止fixtureは期限で終了し、往復等のfixtureが監視を更新するかを実測できる。既存GameTestの失敗は新規probeの結果と分けて報告する。
  - 正常動作を誤停止しない実績/待機形式、復旧操作の不足、原Missionの安全な接続点が判明する。不明なら依存実装を決めず、追加実験を限定する。
  - 一時変更の削除後はPLAN.mdの更新だけが残り、本番状態は変わらない。
  Commit:
  - `docs: record bounded recovery design probes`（一時probe自体はコミットしない）

## Deferred work

- [x] Wave 2, Task 1: タスク内の実績/待機根拠を統一する。
  - Writes: `src/main/java/io/github/zoyluo/aibot/task/Task.java`, `AbstractTask.java`, `StuckWatcher.java`, 全 concrete Task実装のtimeout/evidence policyと必要な成功イベント（現在確認済み: `MoveTask.java`, `GatherQuotaTask.java`, `OreDigTask.java`, `FarmTask.java`, `SmeltTask.java`, `HoldTask.java`, `GuardTask.java`, `FollowTask.java`, `FishTask.java`, `HuntTask.java`, `MiningBarricadeTask.java`, `AcquireWaterTask.java`, `RecoverDropsTask.java`, `BuildTask.java`, `SleepTask.java`, `DescendToYTask.java`, `CreeperDefenseTask.java`, `EmergencyShelterTask.java`, `MiningServiceTask.java`, `DigDownTask.java`, `CreateObsidianTask.java`, `MineTask.java`, `StripMineTask.java`, `CombatTask.java`, `BreedTask.java`, `StockpileTask.java`, `ResupplyTask.java`, `PlaceStationsTask.java`, `IrrigateTask.java`, `LightAreaTask.java`, `ContainerTask.java`, `TradeTask.java`, `RaidCropsTask.java`, `MilkCowTask.java`, `CraftTask.java`, `EatTask.java`, `EvadeTask.java`, `LavaEscapeTask.java`); 対応する `src/test` または `src/gametest` の監視回帰テストと必要なentrypoint登録、および `src/test/java/io/github/zoyluo/aibot/DeepSeekThinkingConfigTest.java` の古いconfig constructor呼び出し。
  - Reads: `BlockMiner.java`, `TaskManager.java`, 既存のtask-owned watchdogとtimeout実装。
  - Change: `progress()`を判定根拠から外し、タスクが返す最小の実績変更と期限付き待機情報で既存監視窓を判定する。`isWaiting()`単独では監視を止めない。有限taskに自己監視を任せる場合は実在する有界watchdogを確認して明示し、無期限のユーザー指示タスクだけをongoingとして明示する。200tickをまたぐ正当な作業があるMine/StripMine/Combat/Breedは、実ブロック破壊・被害・給餌/繁殖成功だけを成果として記録する。採掘/移動のタスク内watchdogは重複させない。
  - Verify: 静止、座標往復、progress往復、無関係な在庫変化、実ブロック破壊の進展、掘削式移動、作物成熟待ち、精錬待ちを固定fixtureで比較し、`./gradlew test` と `./gradlew runGameTest`。
  - Expected: 見せかけの変化では監視が更新されず、taskが報告した実績か具体的な待機条件だけが次の判定を決める。正常な長時間作業を誤停止しない。
  - Commit: `fix: base task watchdog on useful outcomes`。
  - Plan update: 全38 concrete Taskを監査する。isWaiting実装21種は各有限タスクの有界watchdogを確認してtask-managed policyを明示し、無期限ユーザー指示（Hold/Guard/Follow）だけをongoingとする。非waitのうち200tickを超える正当作業のあるMine/StripMine/Combat/Breedだけ成果イベントを追加。他の非wait Taskは無成果で監視窓を超えたらstuckにする。共通のprogress evidence counterはAbstractTaskに保持する。
- Wave 2, Task 2: 原Mission単位の進捗と反復予算を保持する。
  - Writes: `src/main/java/io/github/zoyluo/aibot/goal/GoalExecutor.java`, `src/main/java/io/github/zoyluo/aibot/persist/MissionRecord.java`, `MissionRuntimeRecord.java`, `MissionSpec.java`, 対応するGoalExecutor/persistence tests。
  - Reads: Wave 2 Task 1の実績型、`EpisodeMemory.java`, `DecisionSession.java`, `scripts/persistence_restart_test.sh`。
  - Change: 初回に得た必要前提/探索実績と、同じ障害に対する試行済み手段・上限をactive missionに保持する。位置、表示progress、無関係な所持品や途中task成功だけでは予算を戻さない。シリアライズはバージョン互換を保つ。
  - Verify: 往復、A→B→A、前提条件の実取得後の再試行、再起動後の予算維持、cancel/replaceの永続化境界を固定fixtureと `bash scripts/persistence_restart_test.sh` で確認。
  - Expected: 原目標が前進したかと同じ試行の反復をModが決定でき、再起動で上限を洗い流さない。
  - Commit: `feat: retain mission progress and retry budget`。
- Wave 3, Task 1: 失敗を一段のmission recoveryへ接続する。
  - Writes: `src/main/java/io/github/zoyluo/aibot/goal/GoalExecutor.java`, `src/main/java/io/github/zoyluo/aibot/brain/BrainCoordinator.java`, `DecisionSession.java`, 必要なら `CodexAppServerClient.java`/固定応答テスト。
  - Reads: Wave 2の実績/予算、`GoalPlanner.java`, `ToolRegistry.java`, `MissionRecord.java`, `GoalExecutor.submit`の保護規則。
  - Change: 失敗理由・未達条件・前後の実績・観測済み候補・試行済み手段を一つの復旧要求にし、GoalExecutorが元Missionのauthorityを保持してLLMへ一案のみ問い合わせる。提案をModが検証し、既存typed Goal/Taskとして一段だけ実行する。補助手順後は原目標をfresh stateから再評価する。
  - Verify: 素材補給後の原目標再開、同一案拒否、観測範囲で未発見/権限拒否の分離、実行中のcancel/replaceと遅延応答、未対応の必要操作がない場合のbounded failureを固定応答GameTestで確認。
  - Expected: LLMは次の手段の提案だけを行い、同じ失敗を言い換えて繰り返せず、前提準備を最終成果として誤報告しない。
  - Commit: `feat: recover failed goals through bounded mission actions`。
- Wave 4, Task 1: 統合・永続化・利用者向け挙動を検証する。
  - Writes: 必要な統合修正、`README.md`, `docs/TESTING_AND_EVIDENCE.md`。
  - Reads: Wave 2/3の変更一式と現行の検証手順。
  - Change: 端末結果、復旧中、予算到達を区別し、実装済みの挙動と証拠範囲を文書化する。
  - Verify: `./gradlew test`, `./gradlew runGameTest`, `./gradlew build`, `bash scripts/persistence_restart_test.sh`, `git diff --check`。
  - Expected: 全acceptance criteriaが隔離環境で確認される。統合失敗があればPLANを保ったまま該当Waveへ戻る。
  - Commit: `docs: document bounded mission recovery`（統合修正は別commit）。originへのpushは完了検証後。

### Wave 1 evidence

- Isolated probe (`RecoveryDesignProbeGameTests`, temporary and removed after evidence capture): `./gradlew runGameTest` against only the probe passed, `1/1`; report time `0.943s`. It observed that an unchanged finite task fails as `stuck:<task>` at the configured 200 tick window; position and displayed progress changes reset that window; `isWaiting()` removes its sample each tick, so the generic watcher supplies no deadline while waiting; after wait clears, a new full observation window starts. The fixture toggled `progress()` in its task tick, so a first attempt incorrectly kept oscillating after wait release and timed out; disabling oscillation when clearing the wait corrected the fixture, and the isolated run passed.
- No active task code was changed. `MoveTask.progress()` is elapsed-time-derived and `isWaiting()` is true during digging; `OreDigTask` and `GatherQuotaTask` return true for the full operation and retain their own no-progress/roaming or timeout guards; `SmeltTask` reports a concrete SMELTING phase but also exempts digging approaches; `FarmTask` has an observed immature-crop wait plus an intentional ongoing-tend mode. Therefore Wave 2 must preserve task-owned watchdogs and distinguish concrete wait states from open-ended intent.
- Fresh resource-dependent plans already change with inventory state: `GoalPlanner.planFromState` expands current inventory through `ensureItem`, and existing GameTests compare empty vs carried materials. This supports reusing the deterministic planner after a recovery action; it does not establish that every terrain/resource alternative is available. `GoalExecutor.submit` rejects prerequisite goals against an active goal, so a recovery supplement must remain under original mission ownership rather than use ordinary submit/queue semantics. `finishActive` and existing checkpoint/return guards remain the safe interruption boundary.
- First full `./gradlew runGameTest` executed 589 tests and exited 1: the temporary probe's initial fixture bug above, plus existing `DeathRecoveryMissionGameTests.haveItemSurvivesDeathRecoveryAndPreservesQueuedMineOre` failed with `missing result for resumed mission`. The isolated corrected probe then passed. After probe cleanup, the full rerun executed 588 tests and reproduced only that same existing failure (`missing result for resumed mission`); it is outside the allocated probe scope and no production/test behavior files were changed. During cleanup, the saved manifest copy still contained the temporary entrypoint; the attempted run caught this as `ClassNotFound`, the entrypoint was removed explicitly, and the final full run used the restored manifest. Verify manifest has no probe registration before future cleanup of similar experiments.

## Plan updates

- 2026-09-25: Astraによるソース調査。資源依存の再計画は既に存在し、主な設計課題は進捗の根拠、既存のループ抑制と矛盾しないLLM復旧への移行、原目標と実行権限の維持と判明。全面的なplanner置換を除外した。
- 2026-09-25: Wave 1の一時GameTestでStuckWatcherの座標/progress resetと無期限`isWaiting`免除を再現。初回probeの待機解除後のfixture自身のprogress振動を止め、対象のみ1/1成功。cleanup後のfull runは588件を実行し、既存`haveItemSurvivesDeathRecoveryAndPreservesQueuedMineOre`の`missing result for resumed mission` 1件のみでexit 1。再現したsuite失敗はWave 1の変更範囲外。

### Wave 2 Task 1 evidence

- `AbstractTask` now owns a saturating monotonic useful-outcome counter. `StuckWatcher` observes only evidence increases for `MONITOR_EVIDENCE`; position, `progress()`, and inventory totals no longer reset the shared window. `isWaiting()` no longer silently suspends it.
- Audited all 38 concrete Task classes. All 21 `isWaiting()` implementations explicitly own a bounded watchdog (18) or explicit ongoing user intent (Hold/Guard/Follow, 3). The remaining 17 use the shared evidence window. Mine/StripMine/Combat/Breed record only verified long-running outcomes; observed immature crop waiting is bounded by Farm 12000-tick task deadline; actual Smelt furnace waiting is bounded by its target-count deadline.
- Added six `TaskProgressWatchdogGameTests`: stationary finite wait with position/progress/inventory noise expires; Hold remains active; real mining break increments evidence exactly once; MoveTask clears and crosses a tunnel; observed immature crops and actual SMELTING phases remain protected by their finite owner deadlines. All 6 pass.
- `./gradlew compileGametestJava` passed. `./gradlew test` initially exposed a stale `DeepSeekThinkingConfigTest` constructor call; adding the missing "deepseek" backend restored compilation, and the test suite passes. Final `./gradlew test` passed.
- Final `./gradlew runGameTest` ran 594 tests; the six new cases passed. The sole failure remains the Wave 1 baseline `DeathRecoveryMissionGameTests.haveItemSurvivesDeathRecoveryAndPreservesQueuedMineOre` (`missing result for resumed mission`). Final log: `/tmp/mcaiplayer-wave2-rungametest-final3.log`; XML: `build/test-results/gametest/TEST-aibot-gametest.xml`.
- `git diff --check` passed. The new test entrypoint is registered once; no temporary probe files or backups remain. No production server, jar, configuration, or world was changed.
- 2026-09-25: Wave 2 Task 1 completed with the above verification boundary. The known DeathRecovery failure predates this task and remains visible for later full verification.
