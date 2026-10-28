# Phase 2 — per-medication time offset: TASKS (batches)

Companion to [`offset-phase2-plan.md`](offset-phase2-plan.md). Base `79b2a68`, branch `plan/offset-phase2`.
Planning only: this document is the work order, not the work.

---

## 1. Conventions binding on every batch

**B1. Shape.** Each batch is one commit (or a small ordered series inside one batch when marked), compiles
alone, and adds its own tests. No batch may leave the tree red.

**B2. "Pure decision + thin Room shell".** Any logic that must be tested without a device goes into a
pure function in `app/.../data/model/` or `app/.../capability/reminders/application/` that takes a
snapshot list and returns the rows to write (or throws). The repository method is then only
`transactions.withTransaction { ...dao... }`. This keeps the tests JVM-only and keeps **one** arithmetic
point (`materializedFor` / `gatedFor`).

**B3. No new arithmetic.** A batch may call `materializedFor` / `gatedFor` / `validateTimeOffset`; it may
not compute `base + offset`, may not read `offsetMinutes` for anything other than passing it to those
functions, and may not add an offset parameter to `ReminderTimeUtils`. `TimeOffsetArithmeticLocalityTest`
must stay green and unchanged.

**B4. Frozen specs are read-only.** A batch may not edit `docs/medication-time-offset.md` or
`docs/medication-status-spec.md`. Every acceptance run ends with:

```sh
git diff --stat 79b2a68 -- docs/medication-time-offset.md docs/medication-status-spec.md   # must print nothing
```

**B5. Protected database (standing rule, from PLAN §10).** Every run uses an isolated database and a
test-only package. **No** migration, write, clear or restore of the production database; **no** restore
of old backups; **no** rebuild/reinstall/uninstall of `com.driezy.medlog` — ever, in any batch, without a
separate explicit authorization for that run.

> **Why app-module instrumentation is out of bounds by default:** an instrumented app test starts the
> *production* package's process, which runs `MedLogApplication.onCreate` (`MedLogApplication.kt:23-30`)
> — a WorkManager enqueue today, and after **B3 a startup resync against the production DataStore and
> Room database**. App-module instrumentation (`:app:assembleDebugAndroidTest`, `:app:connectedDebugAndroidTest`,
> `am instrument com.driezy.medlog.test/...`) is therefore **authorization-required**, not default.
> `:core:database` instrumentation is allowed: it is its own package
> (`com.driezy.medlog.core.database.test`), its own data dir, in-memory or temp-file databases only.

**B6. The test harness** (copy-paste; substitute the class filter):

```sh
cd ~/work/anshin

# JVM (default for every batch)
timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*PlanForRoutineTest*'
timeout 600  ~/agw.sh :core:model:test --tests '*TimeOffset*'

# Room-level, isolated test package (only when a batch needs a real transaction)
timeout 900 ~/agw.sh :core:database:assembleDebugAndroidTest
APK=$(ls -t core/database/build/outputs/apk/androidTest/debug/*.apk | head -1); md5sum "$APK"
cat "$APK" | timeout 180 rish -c 'cat > /data/local/tmp/core-db-test.apk'
timeout 60  rish -c 'md5sum /data/local/tmp/core-db-test.apk'      # must equal the local md5
timeout 180 rish -c 'pm install -r -d /data/local/tmp/core-db-test.apk'
timeout 300 rish -c 'am instrument -w -r -e class com.driezy.medlog.data.local.MedicationDaoTransactionTest \
  com.driezy.medlog.core.database.test/androidx.test.runner.AndroidJUnitRunner'
```

PASS for the instrumented form: `OK (N tests)` and `INSTRUMENTATION_CODE: -1`, with the local and device
APK md5s equal before installing.

**B7. PASS is a conjunction, not a vibe.** Every batch's PASS = (a) its acceptance commands exit 0 with
the expected test counts, (b) the batch-specific assertions listed under "PASS condition" hold, (c)
`git diff --stat <base>..HEAD` touches only the files listed for that batch (plus that batch's own
tests), (d) B4's spec-diff is empty, (e) `git status --short` is clean.

---

## 2. Dependency graph

```
B1  primitives (materializedFor override, storedTimesEqual, gatedFor, exception types)
│
├──> B2  resync through the single point (planRoutineBatch + planForRoutine) ──> B3  startup convergence ──> B12 restore policy
│     │
│     └──> B10 routine-change pre-check + the two dialogs
│
├──> B4  editor write path gated at the data layer ──> B11 editor stepper + banner + detail + copy
├──> B5  unarchive recompute (+ reset-and-restore)
├──> B6  resume recompute (setArchivedCache(false)) ──> B7 auto-reconcile reverse reconcile (R-7b)
└──> B8  import: `off` decoded + validated, delete-order proof ──> B9 import: retime by receiver routine + report
```

Blocking edges only (there is no soft dependency that is not drawn). Recommended commit order is the
lexical order B1 → B12. Every batch except B3/B10/B11/B12 is behaviour-preserving while the stored
`offsetMinutes` is 0 for all rows — which is the case on every device until **B11** ships the stepper.
Consequence: B1–B10 can be reverted individually without a data fix-up.

| Batch | Blocks | Blocked by | User-visible change |
|---|---|---|---|
| B1 | B2, B4, B5, B6, B8 | — | none (no production caller) |
| B2 | B3, B10 | B1 | none for all-zero data; resync now offset-aware |
| B3 | B12 | B2 | none (idempotent; 0 writes when nothing changed) |
| B4 | B11 | B1 | none until the UI can set a non-zero offset |
| B5 | — | B1 | archived-with-offset meds get correct times on restore |
| B6 | B7 | B1 | resumed meds get correct times |
| B7 | — | B6 | auto-reconcile no longer leaves old-time alarms |
| B8 | B9 | B1 | imports reject illegal `off` (no payload retiming yet) |
| B9 | — | B8 | imported times are retimed to the local routine (R-16) |
| B10 | — | B2 | routine change can be blocked, with named conflicts |
| B11 | — | B4 | the feature becomes reachable: stepper, preview, banner, copy |
| B12 | — | B3 | none (documented policy) |

---

## 3. Batches

### B1 — materialization primitives (no production caller yet)

**Files**

- `app/src/main/java/com/driezy/medlog/data/model/MedicationScheduleMapper.kt`
- `app/src/test/java/com/driezy/medlog/data/model/MedicationMaterializationTest.kt`
- new `app/src/main/java/com/driezy/medlog/data/model/OffsetConflicts.kt` (`OffsetConflict`,
  `IllegalTimeOffsetException.toConflict(medication)`)

**Changes**

1. `materializedFor(schedule: RoutineSchedule, offsetOverride: Int? = null)`: use
   `offsetOverride ?: offsetMinutes`, and write the effective value back with
   `.copy(offsetMinutes = offset)` after `withResolvedRoutineTimes` (spec §5.0.1 — otherwise a reset to 0
   is not persisted and the next pass conflicts again).
2. `storedTimesEqual(other)`: parse/normalise both `reminderTimes` (split, trim, `LocalTime.parse`,
   `distinct`, `sorted`) and compare with `reminderHour`/`reminderMinute`. Semantics identical to
   `ResyncRemindersUseCase.kt:57,65-67` today, but callable on the **materialized** row.
3. `gatedFor(schedule, override: Int? = null)` as in PLAN §4.3 — the write-entry gate, delegates to
   `materializedFor` for applicable rows, and is the **only** place that rejects a non-zero offset on
   PRN / interval / exact-time rows.
4. `OffsetConflict(id, name, anchor, offsetMinutes, base, resolvedMinuteOfDay, reason)` +
   `toConflict` mapping from `TimeOffsetCheck.Rejected`.

**Invariants** INV-1 (unchanged), INV-6; `offsetOverride` is inert when `null`; `materializedFor` keeps
its early returns (Phase 1 contract, pinned by the existing test).

**Exceptions** `IllegalTimeOffsetException` from `gatedFor` propagates to its caller; this batch writes
nothing, so there is nothing to roll back.

**Rollback** pure revert — no caller, no schema, no data.

**Tests**

- `materializedFor` with `offsetOverride = 0` on a `+30` row ⇒ times equal the baseline and
  `offsetMinutes == 0`.
- `materializedFor` with `offsetOverride = null` on the same row ⇒ unchanged Phase 1 behaviour.
- `storedTimesEqual`: reordered/duplicated stored string ("08:30,08:15,08:30") equals ("08:15,08:30");
  differing `reminderHour` ⇒ false.
- `gatedFor`: exact +15 ⇒ `IllegalTimeOffsetException(NOT_APPLICABLE)`; PRN +15; interval +15;
  anchored +15 on 08:00 ⇒ 08:15 and `offsetMinutes == 15`; anchored +0 ⇒ identical row.
- `OffsetConflict` carries anchor/base/resulting minute for a `CROSS_MIDNIGHT` rejection.

**Acceptance**

```sh
git checkout -b impl/offset-phase2-B1 79b2a68   # or continue on the phase-2 impl branch
timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*MedicationMaterializationTest*' \
                                             --tests '*TimeOffsetArithmeticLocalityTest*'
timeout 600  ~/agw.sh :core:model:test --tests '*TimeOffset*'
```

**PASS** `BUILD SUCCESSFUL`, 0 failures; diff touches only the three source/test files; spec diff empty.

---

### B2 — resync through the single materialization point (the core fix)

**Files**

- `app/.../capability/reminders/application/ResyncRemindersUseCase.kt` (rewrite `invoke`)
- new `app/.../capability/reminders/application/PlanRoutineBatch.kt` (pure function + `PlanRoutineResult`)
- new `app/.../capability/reminders/application/RoutineResyncConflictException.kt`
- `app/.../data/repository/MedicationRepository.kt` (`updateMedications(List)` → `planForRoutine(...)`)
- `app/.../data/repository/MedicationRepositoryImpl.kt`
- `app/src/test/java/com/driezy/medlog/data/repository/FakeMedicationRepository.kt` (interface ripple)
- `app/src/test/.../capability/reminders/application/ResyncRemindersUseCaseTest.kt` (rewrite 3 cases)
- new `app/src/test/.../capability/reminders/application/PlanRoutineBatchTest.kt`
- `core/database/src/androidTest/.../MedicationDaoTransactionTest.kt` (add the batch-rollback case)

**Changes**

1. `planRoutineBatch(snapshot, schedule, changedAt, zeroOffsetFor, write)` — the loop from PLAN §4.2:
   skip PRN/interval/no-anchors; `gatedFor`; `storedTimesEqual` ⇒ skip; `write(materialized)`; collect
   `IllegalTimeOffsetException` into `conflicts`; rethrow anything else; **throw
   `RoutineResyncConflictException(conflicts)` after the loop**.
2. `MedicationRepositoryImpl.planForRoutine` = `transactions.withTransaction { snapshot =
   dao.getAllMedicationsOnce(recipientId); planRoutineBatch(..., write = { dao.updatePlan(it, changedAt) }) }`.
   One `changedAt` per batch. Scope unchanged (`isArchived = 0`, `NO_RECIPIENT` ⇒ empty).
3. `ResyncRemindersUseCase.invoke(schedule)` = `planForRoutine(schedule)` then
   `reconcileReminders.all(ROUTINE_CHANGED)`; **no `runCatching`** (spec §9 ①).
4. Delete `MedicationRepositoryImpl.updateMedications` and its interface member (single caller: resync;
   spec §3.3 item 2). Do **not** add a second batch entry point.
5. `MedicationDao.updateMedications` does not exist in this tree (already removed in Phase 1) — verify,
   do not re-add.

**Invariants** R1–R7 (PLAN §4.3); INV-1/2/5/6; I8 (batch atomicity) via the DAO test.

**Exceptions** `IllegalTimeOffsetException` is collected into `RoutineResyncConflictException` and thrown
**after** the loop, so Room rolls the batch back; `PlanBoundaryNotAdvancedException` propagates as-is;
any other throwable propagates unwrapped — resync contains no `runCatching` at all.

**Rollback** single revert restores the offset-blind behaviour; no schema/data change, and no non-zero
offset exists yet on a real device (B11 not shipped).

**Tests**

- `PlanRoutineBatchTest`:
  - `offsetIsPreservedAcrossRoutineChange`: med `AFTER_BREAKFAST`, `offsetMinutes = 15`, stored 08:15;
    new schedule breakfast 09:00 ⇒ the written row is 09:15 **and** still `offsetMinutes = 15`
    (this test fails against the old `resolve()` code — it is the regression pin).
  - `unchangedMaterializedTimesAreNotWritten`: same schedule ⇒ `write` never called, `PlanRoutineResult.noOp == 1`.
  - `conflictRollsBackTheWholeBatch`: A legal + B illegal ⇒ throws `RoutineResyncConflictException`
    whose list **contains only B**, and A's write *was* attempted (collection, not early abort).
  - `oneBoundaryPerBatch`: all writes receive the same `changedAt`.
  - `pausedOrArchivedRowsAreNotInScope`: PRN/interval/no-anchor meds produce no writes.
  - `nonOffsetIllegalitiesRollBack`: a non-`IllegalTimeOffsetException` propagates (not swallowed).
- `ResyncRemindersUseCaseTest` (rewritten): `planForRoutine` called once before
  `reconcileAll(ROUTINE_CHANGED)` (`inOrder`); a throwing `planForRoutine` propagates **and**
  `reconcileAll` is not called.
- `MedicationDaoTransactionTest` (androidTest): inside `db.withTransaction`, `updatePlan(A)` then throw
  `RoutineResyncConflictException` ⇒ A's row, boundary and revision count all unchanged.

**Acceptance**

```sh
timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*PlanRoutineBatchTest*' --tests '*ResyncRemindersUseCaseTest*'
# + harness B6 (instrumented) for MedicationDaoTransactionTest
```

**PASS** all of the above green; `grep -n 'resolve(' app/.../ResyncRemindersUseCase.kt` prints nothing
(the file no longer holds arithmetic); spec diff empty.

---

### B3 — startup convergence (§3.6)

**Files**

- new `app/.../capability/reminders/application/ConvergeRoutineOnStartupUseCase.kt`
- `app/.../MedLogApplication.kt`
- new `app/src/test/.../capability/reminders/application/ConvergeRoutineOnStartupUseCaseTest.kt`

**Changes**

1. The use case reads the active recipient's stored routine (`ReminderPreferences.routineScheduleFor`,
   see B4 for the interface) and calls `ResyncRemindersUseCase` inside `runCatching`, catching
   `PlanBoundaryNotAdvancedException` and `RoutineResyncConflictException` **only**, logging the conflict
   list (name / anchor / offset / resulting time). This is the sole place a resync exception may be
   swallowed (spec §3.6, §9 ①).
2. `MedLogApplication.onCreate`: keep the existing `enqueue(SYSTEM_EVENT)` where it is and add an
   application-scope launch of the use case. Ordering is safe either way because the resync performs its
   own `all(ROUTINE_CHANGED)` after commit; the enqueue is the net for a failed resync.

**Invariants** R5/I N-6 (idempotent: 0 writes when the stored times already equal the materialization);
no new persisted state (no dirty flag, no migration).

**Exceptions** only `PlanBoundaryNotAdvancedException` and `RoutineResyncConflictException` are caught
(logged with the conflict list); every other throwable escapes to the caller's launcher so it is not
mistaken for a converged state.

**Rollback** revert — removes only the safety net.

**Tests** resync invoked once with the persisted schedule; both exception types are caught and the
`Application` call path completes; any other exception propagates; two consecutive runs ⇒ second run
writes nothing (fake repository records calls).

**Acceptance** `timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*ConvergeRoutineOnStartup*'`

**PASS** green; no test constructs `MedLogDatabase`; no WorkManager enqueue count assertion changes in
existing tests (run `--tests '*Reminder*'` too).

---

### B4 — editor write path gated at the data layer

**Files**

- `core/preferences/src/main/kotlin/com/driezy/medlog/data/repository/PreferenceContracts.kt`
  (`ReminderPreferences.routineScheduleFor(recipientId)` added; `UserPreferencesRepository` already
  implements it at `:488`, so only an `override` keyword is needed there)
- `app/.../data/repository/UserPreferencesRepository.kt` (`override`)
- `app/.../data/repository/MedicationRepositoryImpl.kt` (inject `ReminderPreferences`; gate in
  `addMedication` and `updateMedication`)
- `app/.../feature/medications/editor/AddMedicationViewModel.kt` (state carries `offsetMinutes`; stop
  computing the *written* times; keep computing the *preview*)
- `app/src/test/.../feature/medications/editor/AddMedicationViewModelTest.kt`,
  `app/src/test/.../data/repository/` new `MedicationRepositoryGateTest.kt`

**Changes**

1. `addMedication`: `stamped = medication.stamped()` **first**, then
   `dao.insertMedication(stamped.gatedFor(prefs.routineScheduleFor(stamped.careRecipientId)))`.
2. `updateMedication`: same stamp → routine → `gatedFor` → `dao.updatePlan(gated, clock.millis())`.
   The gate sits here (not in the ViewModel) so that E7 ② — a direct
   `repository.updateMedication(med.copy(offsetMinutes = 15))` on an exact-time row — throws.
3. `AddMedicationViewModel.save`: `offsetMinutes = state.offsetMinutes` in the `copy(...)` block at
   `:550-554`; the times it writes become the *exact-time* truth only (applicable rows are re-derived by
   the repository). Preview still uses a pure `withResolved...`-style helper with the same offset.
4. Read the routine **before** entering the VM's `withTransaction` if measurement shows the DataStore
   read holds the transaction too long; otherwise follow §3.8 and read inside. Record which was chosen in
   the commit message.

**Invariants** INV-2/4/5 for both editor paths; INV-1 (the repository adds no arithmetic); the editor
still saves exactly the user's plan for exact-time rows.

**Exceptions** `IllegalTimeOffsetException` leaves the VM `withTransaction` with zero writes and the draft
intact; `PlanBoundaryNotAdvancedException` uses the existing "plan time did not advance" message. Neither
is converted into a silent success.

**Rollback** revert; the editor returns to writing UI-computed times (behaviour-preserving while all
offsets are 0).

**Tests**

- `MedicationRepositoryGateTest` (mock DAO, passthrough `TransactionRunner`, fake preferences):
  exact +15 ⇒ throws `NOT_APPLICABLE`, **no** DAO call; PRN +15 ⇒ same; anchored +15 ⇒ `updatePlan`
  receives times shifted by 15 and `offsetMinutes = 15`; anchored +0 ⇒ times identical to Phase 1.
- `AddMedicationViewModelTest`: save with a non-zero offset writes `offsetMinutes`; save rejection keeps
  the draft and sets `isSaving = false`; existing save tests unchanged and green.
- `FakeMedicationRepositoryTest` unchanged behaviour.

**Acceptance**

```sh
timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*MedicationRepositoryGateTest*' \
                                             --tests '*AddMedicationViewModelTest*'
```

**PASS** green; the gate test asserts "no DAO interaction" on the rejected paths (verification, not just
the exception type); spec diff empty.

---

### B5 — unarchive recomputes against the current routine (§3.8)

**Files**

- `app/.../data/repository/MedicationRepository.kt` + `MedicationRepositoryImpl.kt`
  (`unarchiveMedication(id: Long, zeroOffset: Boolean = false)`)
- new pure `app/.../data/model/UnarchivePlan.kt` (`fun planUnarchive(row, schedule, zeroOffset): Medication`)
- `app/.../feature/settings/FocusedSettingsViewModels.kt`, `SettingsContract.kt`,
  `SettingsHomeModulesContent.kt` (catch + dialog state + the reset action)
- `app/src/test/.../data/model/UnarchivePlanTest.kt`,
  `app/src/test/.../data/repository/FakeMedicationRepository.kt`,
  `app/src/test/.../feature/settings/*` (existing settings VM tests)
- strings ×4 (`values`, `values-en`, `values-ja`, `values-ko`): `unarchive_illegal_offset_title`,
  `unarchive_reset_offset`

**Changes**

1. `setArchived(id, false)` → `planUnarchive(row, prefs.routineScheduleFor(row.careRecipientId), zeroOffset)`
   → **one** `updatePlan`; `isArchived = false` and the recomputed times land in the same write (1
   revision, old segment keeps `isArchived = true` + pre-change times).
2. Illegal ⇒ `IllegalTimeOffsetException` propagates out of the transaction ⇒ row stays archived; the
   settings VM maps it to `unarchive_illegal_offset_title` with actions "cancel" / "reset offset and
   restore" (`zeroOffset = true`, same single write).
3. The archive path (`true`) is untouched.

**Invariants** INV-2/5, H1–H5 (exactly one revision, boundary advances, old segment faithful); the
rejection writes nothing.

**Exceptions** `IllegalTimeOffsetException` rolls the repository transaction back (the row stays archived
and no revision is written) and the settings VM turns it into the named dialog; a
`PlanBoundaryNotAdvancedException` is reported as a generic failure with no automatic retry.

**Rollback** revert; unarchive returns to "flip the flag only" (pre-existing, R-4 reopen).

**Tests**

- `planUnarchive`: times changed by the routine ⇒ new times + `isArchived = false` in one row; zeroOffset
  ⇒ offset 0 and baseline times; illegal ⇒ throws and returns nothing.
- `MedicationDaoTransactionTest` (androidTest): one `updatePlan` on the planned row ⇒
  `revisions + 1`, previous revision `isArchived == true` + old times, boundary advanced.
- Settings VM: exception ⇒ dialog state, no `unarchiveMedication` retry loop; reset action ⇒ second call
  with `zeroOffset = true`.

**Acceptance**

```sh
timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*UnarchivePlanTest*' --tests '*Settings*'
# + harness B6 for MedicationDaoTransactionTest
```

**PASS** green; exactly one `updatePlan` per successful unarchive (verified by mock count); spec diff empty.

---

### B6 — resume recomputes; `ResumeBlockedByOffsetException` (§3.9)

**Files**

- `app/.../data/repository/MedicationRepository.kt` + `Impl` (`setArchivedCache(false)` branch)
- new `app/.../data/repository/ResumeBlockedByOffsetException.kt`
- new pure `app/.../data/model/RestorePlan.kt` (`fun planRestore(row, schedule): Medication?` — null = no
  time change ⇒ raw SQL path)
- `app/.../feature/medications/application/ReconcileMedicationStatusUseCase.kt` (catch + skip + log)
- `app/.../feature/medications/application/SetMedicationStatusUseCase.kt` and/or its VM (surface the
  blocked dialog; must **not** swallow)
- `app/.../feature/medications/application/CorrectMedicationStatusEventUseCase.kt` (let it roll back)
- strings ×4: `resume_illegal_offset_body` (title reuses `unarchive_illegal_offset_title`)
- tests: new `RestorePlanTest`, `MedicationStatusStatusWriteTest`,
  `MedicationStatusReconciliationTest`, `MedicationStatusCorrectionTest`

**Changes**

1. `setArchivedCache(id, false)`: read row + routine; `planRestore` ⇒ **null** ⇒ today's raw SQL
   `unarchiveMedication` (0 revisions); non-null ⇒ `updatePlan` (1 revision); `IllegalTimeOffsetException`
   ⇒ `ResumeBlockedByOffsetException(medicationId, check)`.
2. Caller ② (auto-reconcile) catches it, logs, skips the med, keeps the cache `true` ⇒ `pausedNow` stays
   true ⇒ nothing is scheduled at an uncomputable time.
3. Callers ①③ run inside a status transaction ⇒ the exception rolls back the event insert and the cache
   flip; they must **not** catch it (the dialog is a UI concern raised from the transaction's failure).
4. Pause (`true`) path unchanged: one SQL statement, 0 revisions.

**Invariants** INV-2/5; M1 (status-spec §0.5): times changed ⇒ exactly 1 revision, unchanged ⇒ 0; status
events and adherence semantics untouched; rejection ⇒ zero writes.

**Exceptions** `IllegalTimeOffsetException` is rethrown as `ResumeBlockedByOffsetException`; callers ①③
let it abort their status transaction (no event, cache unchanged), caller ② catches it per med and skips
without reconciling.

**Rollback** revert; resume returns to "flip the cache only" (R-7 reopens).

**Tests**

- `RestorePlan`: unchanged times ⇒ null; changed ⇒ planned row with new times and `isArchived = false`;
  illegal ⇒ throws.
- Status write: pause ⇒ 0 revisions, resume-with-change ⇒ 1, resume-without-change ⇒ 0, blocked ⇒ event
  count unchanged and still paused.
- Adherence parity: 90-day `(expected, taken, missed)` triples identical before/after resume (existing
  `MedicationStatusDoseParityTest` pattern).

**Acceptance**

```sh
timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*RestorePlanTest*' \
  --tests '*MedicationStatusStatusWriteTest*' --tests '*MedicationStatusReconciliationTest*' \
  --tests '*MedicationStatusCorrectionTest*'
```

**PASS** green; the four revision-count cases asserted by captured DAO interactions; spec diff empty.

---

### B7 — auto-reconcile re-schedules the resumed med (R-7b, §3.11)

**Files**

- `app/.../feature/medications/application/ReconcileMedicationStatusUseCase.kt` (remove `if (derived)` at
  `:42`; reconcile per med after a successful write)
- `app/.../capability/reminders/AndroidReminderReconciler.kt` (inject `Clock`; `pausedNow` uses it
  instead of `System.currentTimeMillis()` at `:57`)
- tests: `MedicationStatusReconciliationTest`, `AndroidReminderReconcilerTest`

**Changes**

1. Per med: `setArchivedCache` inside `runCatching`; on success
   `runCatching { reconcileReminders.medication(id, MEDICATION_CHANGED) }` with a log on failure; on
   `ResumeBlockedByOffsetException` log + skip (no reconcile — the cache is still `true`).
2. `lastAutoReconciledAtMs` still written once at the end, independent of per-med outcomes.
3. `pausedNow` takes the injected clock (test determinism for E14); no behaviour change otherwise.

**Invariants** post-write ordering (`inOrder { setArchivedCache; reconcileMedication }`); idempotence
(second run: 0 writes, 0 reconciles); reconcile never inside a transaction; failure never rolls back the
status write.

**Exceptions** per-med `runCatching` around both the cache write and the reconcile call: a failed write
skips the med (no reconcile), a failed reconcile is logged and left to the queue/startup, and a committed
status write is never rolled back.

**Rollback** revert; the gap reopens (alarms at old times until the next full reconcile).

**Tests** E14 ①–⑤ as specified (`AndroidReminderReconciler` shadow `AlarmManager`, `Map<requestCode,
triggerAtMs>`): immediate arming at the new time; second run no-op; kill between write and reconcile ⇒
next full reconcile converges with no duplicate and no new revision; interactive path not double-triggered;
blocked med skipped while the sibling is scheduled.

**Acceptance**

```sh
timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*MedicationStatusReconciliationTest*' \
                                             --tests '*AndroidReminderReconcilerTest*'
```

**PASS** green, including the in-order assertion and the "still old at 23:00 before the full reconcile"
negative assertion (it must fail if someone calls reconcile before the write).

---

### B8 — import: `off` decoded and validated before any delete

**Files**

- `app/.../feature/medications/application/PlanExportSchema.kt` (`@SerialName("off") val offsetMinutes:
  Int = 0`, encode + decode, `encodeDefaults = false` ⇒ absent when 0)
- `app/.../feature/medications/application/ImportPlanUseCase.kt` (validate the whole package first)
- new `app/.../feature/medications/application/ImportOffsetRejected.kt`
- `app/src/test/.../application/PlanExportCodecTest.kt`, `ImportPlanUseCaseTest.kt`
- strings ×4: `import_rejected_offset`

**Changes**

1. Payload carries `off`; decode defaults to 0 (old codes ⇒ 0 ⇒ always `Allowed`, §5.5).
2. `ImportPlanUseCase`: read the routine **once** for the whole package, then per entry
   `gatedFor(routine)` (applicable) / `requireAllowed(validateTimeOffset(off, routine, anchors, false))`
   (non-applicable); collect **all** rejections; throw `ImportOffsetRejected(list)` **before** calling
   `mergeMedicationsByName` / `replaceActiveMedications`.
3. Keep writing the payload times in this batch (no retiming yet — that is B9). `off` is stored on the
   row so B9 can materialize it; INV-2 is therefore **not yet** claimed for imported rows.
4. Add a KDoc/inline contract on `MedicationDao.replaceActiveMedications` stating that callers must have
   validated before invoking it (the DAO's `@Transaction` rollback is a second layer, not the first).

**Invariants** "no `off` value reaches storage unvalidated"; "validation precedes the delete"; REPLACE's
existing rows survive a rejected import byte-identically.

**Exceptions** `ImportOffsetRejected` aborts before the repository is called (the delete has not run);
the DAO `@Transaction` rollback is the second layer only, not the contract.

**Rollback** revert; import returns to accepting whatever the payload says (with `off` dropped).

**Tests**

- Codec: `off = 0` ⇒ JSON has no `off`; `off = 30` ⇒ round-trips to 30; unknown keys still ignored.
- `ImportPlanUseCaseTest`: `off = 17/90/-75` ⇒ rejected, **`replaceActiveMedications` never called**
  (mockito `never()`), existing rows identical; `off != 0` with `tp = exact` / `isPRN` / `intervalHours > 0`
  ⇒ `NOT_APPLICABLE`, zero writes; legal `off = 30` ⇒ accepted and stored on the row.
- DAO androidTest (existing) `replaceActiveMedicationsRollsBackDeletionWhenAnInsertFails` must stay green.

**Acceptance**

```sh
timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*ImportPlanUseCaseTest*' --tests '*PlanExportCodecTest*'
```

**PASS** green; the `never()` verification is present (that is the ordering proof); spec diff empty.

---

### B9 — import: retime by the receiver's routine + difference report

**Files**

- `app/.../feature/medications/application/ImportPlanUseCase.kt` (`ImportReport(retimed)`, write the
  materialized rows)
- `app/.../feature/medications/home/MedicationQrDialog.kt` (or the import VM) — show the report
- `app/src/test/.../application/ImportPlanUseCaseTest.kt`
- strings ×4: `import_retimed_title`, `import_retimed_line`

**Changes**

1. Applicable entries: write `gatedFor(routine)` (receiver routine, `off` from the payload); payload
   `rt/rh/rm` are **only** compared, never stored.
2. Non-applicable entries: unchanged (payload times are absolute truth).
3. `retimed` = entries where the materialized string differs from the payload `rt`; the UI appends the
   list after success, silently omitting an empty list.

**Invariants** INV-2 now holds for imported rows (E12 ⑦); the payload clock never becomes a stored value
for applicable rows.

**Exceptions** validation has already run with the same `(off, anchors, routine)` that materialization uses,
so materialization cannot throw here; if it does, it aborts the whole package before any write.

**Rollback** revert to B8 behaviour (validated but not retimed) — still legal, still safe.

**Tests** E12 ①–⑦: same-routine round trip is byte-identical and `retimed` is empty; different routine ⇒
stored value is the local materialization and the payload value appears in no column; illegal ⇒ whole
package rejected with existing rows intact; old payload without `off` ⇒ succeeds, retimed listing every
moved row; post-import INV-2 assertion over all active rows.

**Acceptance** `timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*ImportPlanUseCaseTest*'`

**PASS** green, including the "payload value in no column" assertion (query the written rows, do not rely
on the report).

---

### B10 — routine-change pre-check + the two dialogs (§3.7)

**Files**

- new `app/.../capability/reminders/application/CheckRoutineChangeUseCase.kt`
- `app/.../feature/settings/FocusedSettingsViewModels.kt` (the six-step order), `SettingsContract.kt`,
  `SettingsScreen.kt`
- strings ×4: `routine_conflict_*` (5 keys) and `routine_resync_conflict_*` (4 keys incl. reset action)
- tests: new `CheckRoutineChangeUseCaseTest`, settings VM tests

**Changes**

1. `check(candidate)`: recipient's meds **including archived** (`getAllMedicationsFor`), current routine
   from the same source as the settings flow; per med: `offsetMinutes == 0` skip, no anchors / PRN /
   interval skip, `legalNow`/`legalAfter` via `validateTimeOffset`; `conflicts = legalNow is Allowed &&
   legalAfter is Rejected`; `preexisting = legalNow is Rejected`. **No** `materializedFor`, no write.
2. Settings action order: candidate → check → non-empty conflicts ⇒ modal and return → `updateRoutineTime`
   → `resyncReminders` **re-reading** the schedule from DataStore → on `RoutineResyncConflictException`
   show the second dialog (routine saved, times did not move).
3. "Reset offsets and continue" calls `planForRoutine(schedule, zeroOffsetFor = conflictIds)` — one
   transaction, one `changedAt` (requires B2's parameter).

**Invariants** no write before step 4; blocked change leaves DataStore and Room untouched; the
"already-illegal" med never blocks (deadlock avoidance) but lights the banner warning.

**Exceptions** the pre-check never throws for a conflict (it returns lists); a
`RoutineResyncConflictException` at step 5 becomes the second dialog with the DataStore write kept
(deliberately not rolled back); `PlanBoundaryNotAdvancedException` becomes the W5 message; nothing retries
automatically.

**Rollback** revert; routine changes return to "write then resync" with no pre-check.

**Tests** §8 #17: A `BEDTIME +30`, B `BEDTIME +15`, bed → 23:30 ⇒ dialog lists **only A**, DataStore
unchanged, Room unchanged, revisions unchanged; bed → 23:15 ⇒ allowed, each med +1 revision; archived med
still in the scan (see Q6/Q8); pre-existing-illegal med does not block but is reported.

**Acceptance** `timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*CheckRoutineChange*' --tests '*Settings*'`

**PASS** green, including the exact-named conflict list (no over- or under-reporting) and the untouched
DataStore assertion.

---

### B11 — editor stepper, banner, detail line, copy (§5.1/§5.2/§5.6/§7)

**Files**

- `app/.../feature/medications/editor/AddMedicationScreen.kt` + `AddMedicationViewModel.kt` (offset state,
  stepper, preview, disabled slots, wrapped-anchor whole-stepper disable, error state for a stored
  illegal value)
- `app/.../feature/medications/list/` (derived banner: count of `Rejected` rows — pure function of
  `(routine, meds)`)
- `app/.../feature/medications/detail/MedicationDetailScreen.kt` (`08:00 → 08:30` line + neutral badge)
- strings ×4: `time_offset_section_title`, `_stepper_label`, `_preview_format`, `_disclaimer`,
  `_cross_midnight_hint_later/_earlier`, `_illegal_stored`, `_wrapped_anchor_hint`,
  `_conflict_banner`
- tests: `AddMedicationViewModelTest`, list VM test, `MedicationMaterializationTest` (preview == stored)

**Changes**

1. Stepper: all 9 slots rendered; a slot whose `validateTimeOffset` is `Rejected` is `enabled = false`
   with the reason text **on screen** (not a tooltip); wrapped anchor ⇒ the whole stepper disabled with
   `time_offset_wrapped_anchor_hint`; stored-illegal value ⇒ value shown in error state and save blocked.
2. Preview uses the same materialization helper as the write (B4), so preview == stored by construction.
3. Banner: pure derivation, no persistence, no "reset" action (the editor is the only place to change an
   offset).
4. Disclaimer always visible under the stepper and repeated once on the detail screen.

**Invariants** INV-5 (the UI cannot offer a value the data layer would clamp — it does not clamp at all);
no new navigation surface; copy never implies "you may take the medicine later".

**Exceptions** the UI only surfaces what the data layer throws; it never clamps, never rewrites the value
and never retries a rejected save.

**Rollback** revert; the feature becomes unreachable again (B1–B10 remain harmless).

**Tests** §8 #4 + E1/E3 UI assertions: `bed = 23:30` ⇒ `+30/+45/+60` present and disabled with the
reason text; `+15` enabled; multi-period intersection names the crossing period; stored-illegal shows the
error state and blocks save; each new key exists in **all four** locales (a locale-completeness test).

**Acceptance**

```sh
timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*AddMedicationViewModelTest*' --tests '*MyMedications*'
grep -c 'time_offset_' app/src/main/res/values/strings.xml \
  app/src/main/res/values-en/strings.xml app/src/main/res/values-ja/strings.xml \
  app/src/main/res/values-ko/strings.xml      # all four equal
```

**PASS** green; counts equal across the four locales; the disabled-slot assertions check `enabled` and
the reason string, not just the absence of a crash.

---

### B12 — restore: documented trust policy + convergence

**Files**

- `app/.../feature/settings/application/BackupRestoreUseCase.kt` (KDoc only: the decision and why)
- new `app/src/test/.../data/model/RestoredRowGateTest.kt` (or extend `UnarchivePlanTest`/gate test)
- this plan's Q4 answer recorded in the file KDoc

**Changes** no behaviour change. The policy is: a restored file is **trusted as-is**; it is not validated
before the copy; convergence is the B3 startup recompute after the mandatory process restart, visibility
is the B11 banner, and repair is the editor (never an automatic reset).

**Invariants** the restore path never claims INV-2; nothing in the offset feature runs during restore.

**Exceptions** none added; the trust decision means restore failures stay what they are today.

**Rollback** docs-only revert.

**Tests** `gatedFor` classifies a hand-written illegal row (exactly what a foreign DB can contain) as
`Rejected` — proving the banner has a data source for restored rows; `BackupCompatibilityPolicy` still
accepts `5..27` and rejects 28.

**Acceptance** `timeout 1200 ~/agw.sh :app:testDebugUnitTest --tests '*Backup*' --tests '*RestoredRowGate*'`

**PASS** green; the KDoc states the trust decision and names the convergence point; no test opens
`DatabaseSchema.NAME`.

---

## 4. Final gate (after B12)

```sh
cd ~/work/anshin
git diff --stat 79b2a68 -- docs/medication-time-offset.md docs/medication-status-spec.md   # empty
timeout 2400 ~/agw.sh :app:testDebugUnitTest            # full JVM
timeout 900  ~/agw.sh :core:model:test
timeout 900  ~/agw.sh :core:database:testDebugUnitTest
grep -rn 'resolveWithOffset(' --include=*.kt app/src/main core | wc -l    # 2 (definition + materializedFor)
grep -rn 'offsetMinutes' --include=*.kt app/src/main | grep -v materializedFor | grep -v gatedFor
```

PASS: JVM suites green; the arithmetic grep returns exactly the definition and the single call site; the
`offsetMinutes` grep returns only the editor state, the export codec, the banner/preview validation and
`CheckRoutineChangeUseCase` — **no** read path that computes a time. Frozen specs byte-identical.