# AGENTS.md

How work in this repository is accepted. Applies to every agent and human who changes code.
If a change cannot be accepted under these rules, it is not done — regardless of who wrote it
or how green the build looks.

## 1. When a device run is mandatory

| Change | Minimum proof |
|---|---|
| Room schema, migration, DAO SQL, anything that writes or rewrites user rows | Real device, installed over the existing database, with a before/after snapshot (§3) |
| Alarms, notifications, WorkManager, boot receivers, runtime permissions | Real device: the alarm fires, the notification appears, the registry matches (§3) |
| A user-facing flow you touched (navigation, labels, new controls, new state) | Real device: walk the tap path end to end on the installed debug build |
| `:core:database` instrumentation only, no production code change | That module's test APK on device (§8) |
| Copy change, or an internal refactor fully covered by existing JVM tests, with no boot/DI/schema/permission change | JVM: `./gradlew :app:testDebugUnitTest` (or the narrow module test) plus ktlint |

**A compile is never a pass.** `assembleDebug` proves the code compiles; it says nothing about
the app booting, the migration applying, or the reminder firing. A green JVM suite is not a
substitute for any row marked MUST above.

## 2. How the tester has to reach the feature

Every acceptance entry states a human-readable tap path built from on-screen Chinese labels,
starting at app launch — not a screen or class name:

> 启动 → 底部「我的药品」 → 点某味药 → 「编辑」 → 「提醒提前/延后」步进器 → 「保存」

Rules: one path per entry; include preconditions (active member, permission granted, IME state);
mention scrolling or dialogs explicitly; if you cannot name the labels, read them from
`app/src/main/res/values*/` before writing the entry (tabs: `strings.xml:3-4,27-28`;
`我的药品`: `medication_flow_strings.xml:3`).

## 3. Data evidence, before and after

Snapshot before the install and after the interaction, in the same run, into the run's evidence
directory. Compare at least:

- `medications` (`core/database/.../model/Medication.kt:14`) — row count, `reminderTimes`
  (`:54`), `offsetMinutes` (`:67`), `isArchived` (`:91`), `planEffectiveFromMs` (`:109`).
- `medication_plan_revisions` (`MedicationPlanRevision.kt:10`) — count, and the boundary chain:
  `planEffectiveFromMs` never decreases and strictly advances when content changes
  (`docs/medication-time-offset.md:273`).
- `medication_status_events` (`MedicationStatusEvent.kt:40`) — append-only; existing rows must be
  byte-identical after the run (`docs/medication-status-spec.md:95`).
- `medication_logs` (`MedicationLog.kt:10`) — count unchanged unless the flow records a dose.
- `care_task_logs` (`CareTaskLog.kt:19`) when care tasks are involved.
- DataStore `medlog_settings` (`SettingsDataStore.kt:13`) — any key you touched, restored.
- **Alarm registry**: `SharedPreferences` `alarm_projection_registry` (`AlarmScheduler.kt:29`),
  `StringSet` entries serialized as `<recipientId>:<type>:<id>` (`ReminderTarget.kt:17`), plus
  `registered_medication_ids` (`AlarmScheduler.kt:30`). Expected: exactly the derived set for the
  current member, no phantom entries for deleted/paused meds, and the count agrees with the
  reminders actually scheduled. Registry alone is not proof — confirm the notification fires.

Write the expected values down before the run, including what must **not** change. "No change" is
an assertion, not a skip.

## 4. Protect the real database

- The installed app holds the user's live medication history. `/data/data/com.driezy.medlog/databases/medlog.db`
  plus `-wal` and `-shm` (`AppModule.kt:100-104`, `DatabaseSchema.kt:5`) is a **protected asset**.
- **Before any install**, copy all three to `databases/medlog.db.pre-<change>.bak` (and the wal/shm
  alongside), verify the copy is non-empty, and keep it after the run; report where it lives.
- Write only test data you created. Never re-seed, rewrite, "clean up", or delete real rows, never
  `pm clear`, never restore an older backup without explicit authorization in this session.
- Restore every setting you changed and delete keys you added (writing `false` is not a restore).
- Read/write app-private files through `run-as com.driezy.medlog` only. If `run-as` is refused,
  stop and report: read-only evidence is the ceiling.

## 5. Verdict

- **PASS** — every MUST row exercised on the device, evidence stored, negative control shown,
  data and settings restored.
- **BLOCKED** — could not run: keyguard up, another app in front, phone in use, Shizuku down,
  device unreachable. State what was attempted, what is missing, and what would unblock it.
- **FAIL** — observed behaviour contradicts the expected values.

For behavioural tests add a **negative control**: revert the production fix (or keep the old path)
and show the new test goes red for the intended reason, then restore it. A test that has never
been seen failing proves nothing.

Every report ends with an explicit list of unverified items, even on PASS. Never round BLOCKED up
to PASS, and never write "should work".

## 6. Scripts

Acceptance should converge on three scripts, each taking the run directory as an argument and
printing one short machine-checkable summary line:

1. **install** — builds the debug APK and the androidTest APK, stages both through
   `/data/local/tmp`, `pm install -r`, then confirms the installed path and version. Refuses to run
   when no `.pre-*.bak` backup exists.
2. **dump+assert** — dumps the UI to shared storage, asserts the required labels/bounds for the
   tap path, exits non-zero on a missing node. Never parses values out of `rish` stdout.
3. **db snapshot diff** — snapshots db + wal + shm + registry + DataStore into the run directory,
   diffs two snapshots with SQL, prints per-table counts and the columns that changed.

What exists today and must be reused instead of re-invented:

- `~/agw.sh` — the only sanctioned Gradle wrapper: pins the aarch64 `aapt2` override and disables
  SDK download. Bare `./gradlew` fails with `AAPT2 … Daemon startup failed` on this device.
- `~/rishretry.sh '<cmd>'` — retries `rish` until it returns non-empty, exits 1 with
  `RISH_EMPTY_AFTER_RETRIES`. Use it for every device command.

## 7. Environment gotchas (rules, not anecdotes)

- `rish` returns empty roughly one call in three, sometimes truncated or garbled. Never treat empty
  as a value; retry, then fail loudly.
- `pm` cannot read FUSE `/sdcard`; stage APKs and files through `/data/local/tmp` first.
- `input text` cannot inject CJK on this ROM: `KeyCharacterMap` drops the whole string, ASCII
  included. Use the clipboard + `KEYCODE_PASTE`, or disable the IME. `input tap` also does not
  focus Compose text fields — use a ~130 ms press/swipe.
- **Stop instead of fighting** when the keyguard is up, an overlay such as
  `com.smartisanos.sidebar` is visible, or the user is using the phone. Taps land in whatever is in
  front; report BLOCKED with a screenshot.
- Bound every wait (`timeout` on `rish`, install, Gradle, test runs). A single unbounded device
  wait once burned 95 minutes.

## 8. Instrumentation isolation (verified)

- `:core:database` androidTest is **self-instrumenting**: test package
  `com.driezy.medlog.core.database.test`, `targetPackage` = itself, framework-default Application
  (no `android:name`), and its tests use in-memory databases or a test-only database name
  (`MedicationDaoTransactionTest.kt:141`, `MedLogDatabaseMigrationTest.kt:508`). It never touches
  the production data directory — safe to run without asking.
- `:app` androidTest targets `com.driezy.medlog`, so it launches the production
  `MedLogApplication.onCreate` (`MedLogApplication.kt:27-39`: reminder reconcile enqueue, startup
  convergence, periodic widget worker) against the real DI graph and real database. Running it is
  **authorization-required**, not a default.
- Runner for both is `androidx.test.runner.AndroidJUnitRunner` (`app/build.gradle.kts:39`,
  `core/database/build.gradle.kts:16`). `am instrument` prints `INSTRUMENTATION_CODE: -1` for
  RESULT_OK; `0` means the run failed to start or crashed. Judge by test counts, not the code alone.

## 9. Branches, specs, evidence

- Never commit on `main`. One batch per branch off the current integration branch
  (`impl/offset-bN`, `impl/offset-ui`); a batch must build, test and revert on its own.
- Frozen specs — `docs/medication-status-spec.md`, the frozen section of `docs/care-people.md:116-124`,
  and the frozen `docs/medication-time-offset.md` — are read-only for implementation branches.
  Propose a separate docs commit instead of editing them in place.
- Evidence goes in a per-run directory: `docs/ui-qa/<yyyy-mm>-<topic>/evidence/`, screenshots named
  `NN-<step>.webp`, with the tap path and the before/after data dump as text beside them.
- No doc padding, no restating code, no plan presented as the result.
