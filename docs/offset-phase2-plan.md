# Phase 2 — per-medication time offset: implementation plan (PLAN)

Status: **planning only**. No business code in this commit; no frozen spec touched; nothing merged.
Companion document: [`offset-phase2-tasks.md`](offset-phase2-tasks.md) (batches, graph, commands, PASS conditions).

Base: `impl/time-offset-phase1` @ `79b2a68` (Phase 1 code + both frozen specs present).
Spec sources of truth:

| Document | Where to read it |
|---|---|
| `docs/medication-time-offset.md` | this branch, **plus** the M2 errata section appended at `spec/medication-time-offset` @ `9000cfb` (§"M2 勘误") |
| `docs/medication-status-spec.md` | this branch (identical on both branches; carries M1 = §0.5) |

> **Errata handling.** The M2 errata (three textual contradictions, rules unchanged) is **not on this
> branch**. It does not need merging to be followed: §2.2's evaluation order is now
> `WRAPPED_ANCHOR` **before** `CROSS_MIDNIGHT`, and §6 E10 ⑤ is split into `bed = 00:30` (wrap) and
> `bed = 23:50` (cross-midnight). Phase 1 already implements exactly that
> (`core/model/.../TimeOffset.kt:75-82` wrap branch precedes the crossing branch;
> `core/model/src/test/.../TimeOffsetValidationTest.kt:142-158` uses `bed = 23:50`).
> Implementers must not re-derive the order from the un-errata'd text on this branch.

Language: English prose, Chinese quoted only where the frozen spec's own wording is the requirement.
Identifiers, file paths and line numbers are exact as of `79b2a68`.

---

## 0. What Phase 1 already delivered (audited, so Phase 2 does not redo it)

| Delivered | Evidence |
|---|---|
| `offsetMinutes` column + `MIGRATION_26_27`, `DatabaseSchema.VERSION = 27`, exported `27.json` | `core/database/.../MedLogDatabase.kt:381`, `DatabaseSchema.kt:6` |
| `resolveUnwrapped` / `resolve` / `resolveWithOffset` (integer domain, single `LocalTime` construction) | `core/model/.../RoutineTimeSlot.kt:56-93` |
| `validateTimeOffset` + `TimeOffsetCheck` + `IllegalTimeOffsetException` + `requireAllowed`, no clamping | `core/model/.../TimeOffset.kt:59-108` |
| `materializedFor(schedule)` — single materialization point | `app/.../data/model/MedicationScheduleMapper.kt:59-65` |
| three-branch `updatePlan` returning `PlanWriteResult` / throwing `PlanBoundaryNotAdvancedException` | `core/database/.../MedicationDao.kt:92-108` |
| `ReminderTimeUtils` reduced to a thin delegate with **no** offset parameter | `app/.../util/ReminderTimeUtils.kt:35-39` |
| `resolveWithOffset` locality test | `app/src/test/.../TimeOffsetArithmeticLocalityTest.kt` |

**Not delivered** (this is the whole of Phase 2): `materializedFor` has **no production caller**;
`requireAllowed` has exactly one caller (inside `materializedFor`); the editor has no stepper and does
not write `offsetMinutes`; `ResyncRemindersUseCase` still computes `anchors.map(schedule::resolve)`;
unarchive/resume/import are not materialized; no conflict/blocked exceptions, no dialogs, no banner,
no copy keys, no `CheckRoutineChangeUseCase`, no startup recompute, no R-7b fix.

---

## 1. Definitions used by the call matrix

Three predicates, deliberately **not** the same thing:

| Term | Definition | Required treatment |
|---|---|---|
| **P1 — plan-writing** | the entry can write `reminderTimes` / `reminderHour` / `reminderMinute`, i.e. it materializes absolute times | must go through `materializedFor`; must gate (see below) |
| **P2 — plan-adjacent** | the entry goes through `updatePlan` (so it can create a plan revision) but writes **only** non-time plan fields (`isArchived`, dose, frequency, dates) | **no** materialization, **no** gate — it cannot introduce a new `offsetMinutes` |
| **S — status-only** | the entry writes the status event table and/or the `isArchived` cache column / stock column by raw SQL | no plan semantics at all |

Gate rule (derived from Phase 1 acceptance, `phase1-evidence/phase2-entry-gates.md`):

> An entry must call `requireAllowed(validateTimeOffset(...))` **iff** it can *introduce or change* an
> `offsetMinutes` value that reaches a stored row (user edit, import payload, external input).
> Re-writing the already-validated stored value (archive, stock, pause) is **not** a reason to gate —
> that would be duplicate validation with no new information.

**Trap that the audit makes explicit:** `materializedFor` is **not** a gate for non-applicable rows.
It early-returns at `MedicationScheduleMapper.kt:60` (`isPRN || intervalHours > 0`) and `:62`
(no anchors) **before** `requireAllowed` at `:63`. So `offsetMinutes = 15` on an exact-time / PRN /
interval row survives `materializedFor` untouched. Every P1 entry therefore needs
`gatedFor(schedule)` (§4.2), not `materializedFor` alone.

---

## 2. Call matrix — every offset write entry

`REV` = plan revision row; `—` = not involved.

| # | Entry | file:line | Class | Times | REV | `requireAllowed` | `materializedFor` | `updatePlan` | Transaction boundary |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Editor save → **create** | `AddMedicationViewModel.kt:506,574` → `MedicationRepositoryImpl.kt:77-78` → `MedicationDao.kt:36` | **P1** | ✓ | — | **yes**, data layer (`updateMedication`/`addMedication`), before insert | **yes** | — (insert) | VM `withTransaction` `:525-583`; single INSERT |
| 2 | Editor save → **edit** | `AddMedicationViewModel.kt:577` → `MedicationRepositoryImpl.kt:80-81` → `MedicationDao.kt:92` | **P1** | ✓ | ✓ 1 | **yes**, before write | **yes** | ✓ | VM txn `:525-583` nesting `updatePlan`'s `@Transaction` |
| 3 | **Routine resync** | `ResyncRemindersUseCase.kt:43-62` (`:56` resolve, `:57` compare, `:60` write) → `MedicationRepositoryImpl.kt:84-87` | **P1** | ✓ | ✓ 1/changed med | **yes**, in-txn per row | **yes** | ✓ | one `withTransaction`, one `changedAt` for the batch |
| 4 | **Unarchive** (“restore from archive”) | `FocusedSettingsViewModels.kt:210-216` → `MedicationRepositoryImpl.kt:99,111-116` | **P1** | ✓ (must recompute) | ✓ 1 | **yes** | **yes** (§3.8) | ✓ | repository `withTransaction` (single `updatePlan`) |
| 5 | **Resume** (3 callers) | `MedicationRepositoryImpl.kt:101-109` ← `SetMedicationStatusUseCase.kt:197`, `ReconcileMedicationStatusUseCase.kt:41`, `CorrectMedicationStatusEventUseCase.kt:86` | **S today → P1 after §3.9** | ✓ conditional | ✓ conditional (0 or 1) | **yes**, on the `archived == false` branch only | **yes** (same branch) | ✓ only when times change | status txn (callers ①③) / no txn (caller ②) |
| 6 | **Import MERGE** | `ImportPlanUseCase.kt:32` → `MedicationDao.kt:44-50` | **P1** | ✓ | — (insert) | **yes**, whole payload first | **yes** | — | DAO `@Transaction` (insert-only) |
| 7 | **Import REPLACE** | `ImportPlanUseCase.kt:33` → `MedicationDao.kt:54-57` | **P1** | ✓ | — (insert) | **yes**, **before the delete** | **yes** | — | DAO `@Transaction` = DELETE + N INSERTs |
| 8 | **Startup recompute** (§3.6, new) | `MedLogApplication.kt:23-30` | **P1** | ✓ | ✓ as in #3 | **yes** (via #3) | **yes** (via #3) | ✓ (via #3) | same single txn as #3; exception catch is the only allowed swallow |
| 9 | **Whole-DB restore** | `BackupRestoreUseCase.kt:64-110` | **P1 by file copy** | ✓ (foreign values) | — | **no** (no per-row path exists) | **no** | — | none; file copy + `enqueue(DATA_RESTORED)` |
| 10 | **Migration v26→v27** | `MedLogDatabase.kt:381` | **P1 constructively** | — | — | **no** | **no** | — | Room migration txn |
| 11 | **Archive** | `MedicationRepositoryImpl.kt:97,111-116` | **P2** | — | ✓ 1 | **no** | **no** | ✓ | repository `withTransaction` |
| 12 | **Pause** | `MedicationRepositoryImpl.kt:101-106` → `MedicationDao.kt:116` | **S** | — | — | **no** | **no** | — | caller's status txn / none |
| 13 | **Status event write** (user pause/resume, event correction) | `SetMedicationStatusUseCase.kt:161-200`, `CorrectMedicationStatusEventUseCase.kt:75-91` | **S** | — | — | **no** here (transitive #5) | **no** | — | `withTransaction` (event insert + cache flip) |
| 14 | **Auto-reconcile cache correction** | `ReconcileMedicationStatusUseCase.kt:39-41` | **S** | — | — | **no** here (transitive #5) | **no** | — | no txn (§3.11) |
| 15 | **Stock update** | `MedicationRepositoryImpl.kt:119-123` → `MedicationDao.kt:122` | — | — | — (by design) | **no** | **no** | — | single UPDATE |
| 16 | **Delete medication** | `MedicationRepositoryImpl.kt:94` → `MedicationDao.kt:113` | — | — | cascade | **no** | **no** | — | single DELETE (FK cascade) |
| 17 | **`CheckRoutineChangeUseCase`** (new, §3.7) | — | **read-only** | — | — | uses `validateTimeOffset` on `(offset, schedule, anchors)`, **never** `materializedFor` (it must return a candidate list, not throw on the first row) | **no** | — | none (pure reads) |
| 18 | `ReminderTimeUtils.timePeriodToReminderTime` | `ReminderTimeUtils.kt:35-39` | **presentation** | — | — | — | **must not** gain an offset parameter or an offset term | — | — |

### 2.1 Why each classification is what it is (from the code, not from intent)

- **#1 vs #2 are different entries** because they have different write shapes: create is
  `insertMedication` (`MedicationDao.kt:36`, `OnConflictStrategy.REPLACE`) with **no** `updatePlan`, so
  no revision and no boundary question; edit is `updateMedication` → `updatePlan`, so exactly one
  revision when plan content changes. The gate must therefore sit at the **repository** (one place
  covering both), which is also what E7 ② demands: `repository.updateMedication(med.copy(offsetMinutes = 15))`
  must throw without the caller passing a schedule.
- **#3 is currently offset-blind**: `ResyncRemindersUseCase.kt:56` computes `anchors.map(schedule::resolve)`
  and `:57` compares that against the stored times. Two defects in one line: (a) the write drops the
  offset (silent loss of user intent, no error), and (b) the equality test compares the *baseline*, so
  a row with `offsetMinutes != 0` is judged "unchanged" the moment the baseline matches — it can never
  be refreshed. §4 fixes both by moving the comparison to the materialized row.
- **#4 must recompute** because resync reads `WHERE isArchived = 0` (`MedicationDao.kt:17`) and
  `getMedicationsFor` → `getAllMedicationsOnce` (`MedicationRepositoryImpl.kt:47-53`) — archived rows are
  outside every recompute path, so their times freeze at archive time (§3.8, E9).
- **#5 is a hybrid, and that is the point**: today `setArchivedCache` is a raw
  `UPDATE medications SET isArchived = ?` (`MedicationDao.kt:115-121`) with 0 revisions; after §3.9 the
  `false` branch materializes and may write 1 revision, while `true` stays a raw SQL line. The audit
  classifies it "S today → P1 after" so that the batch does not accidentally gate the pause direction.
- **#6/#7 share a gate but not a write shape**: MERGE inserts only (`:44-50`), REPLACE deletes then
  inserts (`:54-57`). REPLACE is the only entry whose **destructive step precedes its inserts**, which
  is why validation is ordered before the delete (§7).
- **#9 is not gated and cannot be**: it copies a foreign SQLite file over `medlog.db`
  (`BackupRestoreUseCase.kt:91-100`). No row-level code in the offset feature runs. The `offsetMinutes`
  values that arrive were validated against *another* device's routine (or none). It is therefore a
  **convergence point**, not a write entry — see §6 and Q4.
- **#11 goes through `updatePlan` and still needs no gate**: `setArchived(id, true)` copies only
  `isArchived`; `reminderTimes` is carried through untouched and `offsetMinutes` is re-written with its
  already-validated stored value. Gating here would reject nothing new and would make archive start
  failing on rows that §3.8 deliberately allows to stay archived with a now-illegal offset.
- **#12/#13/#14 are status-only *at their own level*.** They append to `medication_status_events` and
  flip the cache column; they never touch `reminderTimes`. They are nevertheless **transitive callers of
  #5**, which is exactly why the gate belongs in `setArchivedCache(false)` and not in three use cases
  (three copies of the same arithmetic is what §5.0 forbids).
- **#15/#16 need nothing**: stock is a dedicated SQL column with a regression test that it creates no
  revision (`MedicationDaoTransactionTest.kt:50-77`); delete removes the row and its revision chain by
  FK cascade.
- **#18 must stay offset-free.** Its parity test (§8 #12 of the spec) is the structural guarantee that
  there is no second parser; the editor *preview* must be produced from the same materialization
  function, not from this delegate.

### 2.2 Entry count

- **Plan-writing (materialize): 10** — #1, #2, #3, #4, #5(after), #6, #7, #8, #9(by copy), #10(constructive).
- **Gated (can introduce an offset): 8** — #1, #2, #3, #4, #5, #6, #7, #8. (#9/#10 cannot be gated; #11-#18 must not be.)
- **Status-only / non-plan: 8** — #11 … #18.

The gap between "10 materialize" and "8 gated" is the audit's main finding: migration and restore write
absolute times without any gate, so they need **verification** (§6) rather than **validation**.

---

## 3. The single materialization point — invariant statement

- **INV-1 (one arithmetic point).** `resolveWithOffset` is called from exactly one place in main
  sources: `Medication.materializedFor`. No other main-source line contains `+ offsetMinutes`
  (`TimeOffsetArithmeticLocalityTest` already enforces this; the Phase 2 batches must not weaken it).
- **INV-2 (materialized cache).** For every stored row with anchors and `!isPRN && intervalHours == 0`:
  `reminderTimes/hour/minute == materialize(schedule_of_its_recipient, anchors, offsetMinutes)`.
  The only accepted violation window is W4′ (§5).
- **INV-3 (no read path consumes the offset).** `toDomainSchedule`, `FuturePlanCalculator`,
  `AlarmScheduler`, widget, reconcile page, projection, adherence: none read `offsetMinutes`
  (verified: the only `offsetMinutes` reads in main sources are `materializedFor`
  `MedicationScheduleMapper.kt:63`). Consequence: a corrupted offset can never double-count.
- **INV-4 (gate coverage).** Every entry that can introduce an offset calls `gatedFor` (§4.2) before
  its first write.
- **INV-5 (no clamp).** Illegal offsets throw `IllegalTimeOffsetException`; the stored value and the
  surrounding transaction are unchanged. No `coerceIn`, no rounding, no direction swap, anywhere.
- **INV-6 (idempotence).** Materializing the same `(schedule, anchors, offset)` twice yields identical
  `reminderTimes` ⇒ the resync/startup passes are safe to repeat with 0 writes.

---

## 4. The `ResyncRemindersUseCase` fix (offset-blind → single point)

### 4.1 Current defect

```kotlin
// ResyncRemindersUseCase.kt:51-59 (today)
val updates = meds.mapNotNull { med ->
    if (med.isPRN || med.intervalHours > 0) return@mapNotNull null
    val anchors = TimePeriods.parse(med.timePeriod).mapNotNull { ... }
    if (anchors.isEmpty()) return@mapNotNull null
    val resolved = anchors.map(schedule::resolve).distinct().sorted()   // ← offset term missing
    if (resolved == med.storedReminderTimes()) return@mapNotNull null  // ← compares baseline
    med.withResolvedRoutineTimes(resolved)                             // ← writes baseline
}
if (updates.isNotEmpty()) medicationRepository.updateMedications(updates)  // txn starts too late
reconcileReminders.all(ReminderReconcileReason.ROUTINE_CHANGED)
```

Three failure modes, in increasing severity: (a) a med with `+15` gets its times overwritten with the
baseline — the offset column then contradicts the stored times (INV-2 broken, silently);
(b) the same med is never re-materialized on later routine changes because the equality test keeps
comparing baselines; (c) validation happens **outside** the transaction that writes, so a row committed
between the read and the batch is never judged (W4).

### 4.2 Required shape

Replace the list-based write with one repository operation that owns read + validate + write:

```kotlin
// MedicationRepository (replaces updateMedications(List<Medication>); it has exactly one caller)
suspend fun planForRoutine(
    schedule: RoutineSchedule,
    zeroOffsetFor: Set<Long> = emptySet(),   // only the two explicit "reset and continue" dialogs
): PlanRoutineResult                          // (advanced: Int, noOp: Int)
```

```kotlin
// MedicationRepositoryImpl — read + materialize + updatePlan all inside one transaction
override suspend fun planForRoutine(schedule, zeroOffsetFor) = transactions.withTransaction {
    val recipientId = activeRecipient.current()
    val snapshot = if (recipientId == ActiveRecipientStore.NO_RECIPIENT) emptyList()
                   else medicationDao.getAllMedicationsOnce(recipientId)   // same scope as today
    val changedAt = clock.millis()                                          // one per batch
    val conflicts = mutableListOf<OffsetConflict>()
    var advanced = 0; var noOp = 0
    snapshot.forEach { med ->
        val anchors = TimePeriods.parse(med.timePeriod).mapNotNull { it.toAnchorOrNull() }
        if (med.isPRN || med.intervalHours > 0 || anchors.isEmpty()) return@forEach
        val materialized = runCatching {
            med.gatedFor(schedule, override = if (med.id in zeroOffsetFor) 0 else null)
        }.getOrElse { e ->
            if (e is IllegalTimeOffsetException) { conflicts += e.toConflict(med); return@forEach }
            throw e                                                         // anything else rolls back
        }
        if (materialized.storedTimesEqual(med)) return@forEach              // offset-aware no-op
        if (medicationDao.updatePlan(materialized, changedAt) == PlanWriteResult.Advanced) advanced++ else noOp++
    }
    if (conflicts.isNotEmpty()) throw RoutineResyncConflictException(conflicts)  // ⇒ whole batch rolls back
    PlanRoutineResult(advanced, noOp)
}
```

```kotlin
// ResyncRemindersUseCase — no arithmetic, no catch (spec §9 ①)
suspend operator fun invoke(schedule: RoutineSchedule) {
    medicationRepository.planForRoutine(schedule)
    reconcileReminders.all(ReminderReconcileReason.ROUTINE_CHANGED)   // after commit, never inside
}
```

### 4.3 Invariants this fix must satisfy (each gets a test)

| # | Invariant | Fails if… |
|---|---|---|
| R1 | `ResyncRemindersUseCase` contains **no** `resolve(` / `+` / `.map` over anchors | the diff keeps the old line "for the compare" |
| R2 | the "unchanged" test is on the **materialized** row (`storedTimesEqual`), never on the baseline | a `+15` med is skipped forever |
| R3 | the decision set ≡ the write set, inside one transaction | a pre-scan outside `withTransaction` returns |
| R4 | one `changedAt` per batch, so N meds get N revisions at the same boundary | per-row `clock.millis()` |
| R5 | 0 `updatePlan` calls and 0 revisions when nothing changed | an unconditional `updatePlan` |
| R6 | any conflict ⇒ **zero** rows written (not "skip this one") | `continue` without collecting |
| R7 | no read path consumes `offsetMinutes` (INV-3) after the change | the comparison starts reading the column instead of the times |

`gatedFor` (new, pure, `app/.../data/model/MedicationScheduleMapper.kt`) is the write-entry gate that
`materializedFor` deliberately is not:

```kotlin
fun Medication.gatedFor(schedule: RoutineSchedule, override: Int? = null): Medication {
    val anchors = TimePeriods.parse(timePeriod).mapNotNull { it.toAnchorOrNull() }
    val applicable = !isPRN && intervalHours <= 0 && anchors.isNotEmpty()
    requireAllowed(validateTimeOffset(override ?: offsetMinutes, schedule, anchors, applicable))
    return if (applicable) materializedFor(schedule, override) else this
}
```

- For applicable rows it is a superset of `materializedFor` (validates, then delegates).
- For non-applicable rows it is the **only** place that rejects `offset != 0` (fixes the
  `phase2-entry-gates.md` finding without touching `materializedFor`'s early returns, which the
  existing `MedicationMaterializationTest` pins).
- `materializedFor` stays the only place that **adds** the offset ⇒ INV-1 holds.

---

## 5. Cross-store failure windows (DataStore routine ↔ Room times)

The routine lives in DataStore (`UserPreferencesRepository.kt:488-504,616-640`); the times and the
offset live in Room. `DataStore.edit` cannot join a Room transaction. The plan does not pretend
otherwise: it (a) forbids writing an unmaterializable row, (b) forbids silent states, (c) guarantees
convergence.

| Window | What is committed | User-visible state | Convergence |
|---|---|---|---|
| **W1** routine written, resync txn fails / rejected (§3.2) | routine = new; times = old **but legal under the new routine** (the pre-check proved it); no revision | resync throws ⇒ settings shows "routine saved, times will update on next sync" | startup recompute (B4) |
| **W2** resync committed, reconcile fails / process killed | times = new; alarms = old | reminders at old times until next reconcile | existing queue (`REPLACE`) + boot/startup full reconcile |
| **W3** process killed right after the DataStore write | as W1 | as W1 | as W1 |
| **W4** a conflicting med is inserted between the pre-check and the resync transaction | routine = new; **nothing else** — the batch rolls back | named conflict dialog (`routine_resync_conflict_*`) + list banner | fix offset (1 revision) then resync again; or "reset offset and continue" |
| **W4′** that insert lands *after* the resync transaction's first read (fixed read view) | routine = new; the inserted row keeps whatever its own writer computed — possibly "new routine + illegal offset" | derived banner (§5.6), survives restart | startup recompute + editor error state |
| **W5** clock rollback ⇒ `PlanBoundaryNotAdvancedException` | routine = new; times = old (legal) | explicit save/resync failure message | stops when the clock passes the boundary (deliberate) |
| **W6** (new, import) routine re-read between import validation and the DAO write | times materialized under the routine read at validation time | possibly "legal but stale" | next resync / startup recompute |
| **W7** (new, restore) foreign DB copied in | times/offsets belong to another device or an older routine | banner counts illegal offsets | startup recompute + editor; see Q4 |
| **W8** (new, editor) draft opened before a routine change, saved after | times materialized with **today's** routine (repository re-reads it) | correct by construction — this is why the gate lives at the repository, not in the ViewModel | — |

Reverse order (Room first, DataStore second) is rejected for the same reason as the spec (§3.7): the
stored times would be computed against a routine that may never be persisted, and nothing would ever
repair it except the startup recompute — which is the same as flipping the order back.

---

## 6. Illegal offsets, at every entry

Rejected, never clamped, never silently dropped. Per entry:

| Entry | Rejection type | Nothing written | User sees |
|---|---|---|---|
| editor save | `IllegalTimeOffsetException` | draft kept, `isSaving=false`, row unchanged | error state on the stepper; save blocked (UI also disables the slot) |
| resync | collected ⇒ `RoutineResyncConflictException` | **whole batch** rolled back; routine stays saved | `routine_resync_conflict_*` dialog (named med / anchor / offset / resulting time) + banner |
| unarchive | `IllegalTimeOffsetException` from `materializedFor` | row stays `isArchived = true` | `unarchive_illegal_offset_title` + "reset offset and restore" |
| resume | `ResumeBlockedByOffsetException` | status txn rolls back: no event, cache still paused, plan untouched | `resume_illegal_offset_body` (open the med, no in-dialog offset editing) |
| auto-reconcile (resume direction) | `ResumeBlockedByOffsetException` | cache stays `true` ⇒ not scheduled | log + banner + editor; bounded deviation `isArchived != derived` in the "less scheduled" direction |
| import | `ImportOffsetRejected(name, check)` | **whole package**, before the `REPLACE` delete | `import_rejected_offset` |
| restore | n/a (no code path) | — | banner |
| migration | n/a (`DEFAULT 0` is constructively legal) | — | — |

Stored-illegal rows (W4′/restore/direct DB write) are **not repaired automatically**: `resync` refuses
the batch, the banner counts them, the editor shows the stored value in an error state and blocks save
until a legal slot is chosen. Reset to 0 is only ever an explicit user action (two dialogs), never an
automatic fallback.

---

## 7. History is never rewritten (plan-revision boundary rules)

| # | Rule | Where it is enforced |
|---|---|---|
| H1 | revisions are insert-only; no `UPDATE`/`DELETE` statement exists for them | `MedicationDao.kt` (only `insertPlanRevision` / `observePlanRevisions`) |
| H2 | `planEffectiveFromMs` is monotone; a **content** change must strictly advance it | `updatePlan` branch 2 vs branch 3 (`:100-108`) |
| H3 | content-equal writes are in-place, `NoOp`, boundary untouched | branch 1 (`:97-99`) |
| H4 | one `changedAt` per batch ⇒ no sub-millisecond fragmented segments | `planForRoutine` takes `clock.millis()` once |
| H5 | the previous plan is snapshotted with `until = changedAt` **before** the boundary moves | branch 2 order (`insertPlanRevision` then `updateMedication`) |
| H6 | `offsetMinutes` is **not** in the revision snapshot (`planRevision()` `MedicationPlanRevision.kt:42-60`); history replays from `reminderTimes` alone | by construction; consequence: an offset-only change with unchanged times is `NoOp` (pinned by `MedicationPlanBoundaryTest.kt:113-121`) and must not be treated as a plan change |
| H7 | import creates **new rows** (`planEffectiveFromMs = 0L` default, `Medication.kt:109`), it does not amend existing history; `FuturePlanCalculator` clamps `lower = maxOf(start, planEffectiveFromMs)` so the `[0, changedAt)` segment cannot project past doses | `MedicationDao.kt:44-57`, `FuturePlanCalculator.kt:58` |
| H8 | a rejected write changes **nothing** (row, revisions, boundary), so a retry starts from an unchanged chain | branch 3 throw inside `@Transaction` |

Non-retroactive consequence for the offset feature: changing an offset, or changing the routine, can
only create a boundary at "now". No entry in the matrix may write a revision with a past `changedAt` or
recompute times for a period before the current boundary. The "change day may gain one extra pending
dose" behaviour (spec §5.4) is the same rule as any single-medication time edit, not a history rewrite.

---

## 8. Import: validation strictly before `REPLACE` deletes

Facts: `ImportPlanUseCase.kt:25-34` maps the payload and then calls
`medicationRepo.replaceActiveMedications(newMeds)` → `MedicationDao.kt:54-57`
(`deleteActiveMedications` then N inserts, inside one `@Transaction`).

Why "Room would roll back anyway" is **not** a sufficient argument, and why the order must be provable
from the code:

1. `replaceActiveMedications` is a DAO `@Transaction`, but a validation throw *inside* it aborts the
   loop after a delete has already executed; correctness then depends on Room's rollback, not on the
   absence of a destructive step. E12 ③ asserts the rows are still there — a property that should hold
   by construction, not by rollback semantics.
2. The package-level error must name **every** rejected entry at once (spec §5.5); aborting on the first
   one mid-loop cannot produce that list.
3. `reconcileReminders.all(...)` runs after commit (`:36`); a partially executed delete+insert that is
   later rolled back would still have already cancelled nothing (no reconcile yet) — but the alarms for
   the deleted rows would be gone for the whole window. Not a state we want to create at all.

Required ordering (B8):

```
1. routine := routineScheduleFor(activeRecipient)      // one read, whole package
2. per entry: anchors / applicable
   - non-applicable: requireAllowed(validateTimeOffset(off, routine, anchors, applicable = false))
   - applicable:     gatedFor(routine)                  // validate + materialize, no write
3. any rejection ⇒ throw ImportOffsetRejected(list) ⇒ return; repository never called
4. only now: mergeMedicationsByName | replaceActiveMedications
5. reconcileReminders.all(MEDICATION_CHANGED)
```

Test that proves the order (not just the outcome): a mock verification that the repository's
`replaceActiveMedications` is **never** invoked when one entry is rejected, plus the existing DAO test
`MedicationDaoTransactionTest.replaceActiveMedicationsRollsBackDeletionWhenAnInsertFails` retained as
the second layer.

---

## 9. Reminder reconciliation paths (who re-schedules, and when)

| Entry | Trigger after commit | Failure fallback | Change in Phase 2 |
|---|---|---|---|
| resync | `reconcileReminders.all(ROUTINE_CHANGED)` (`ResyncRemindersUseCase.kt:61`) | must **not** run if the txn failed ⇒ "DB didn't move, alarms didn't move" | unchanged line, now after the new `planForRoutine` |
| unarchive | `reconcileReminders.medication(MEDICATION_CHANGED)` at the call site (`FocusedSettingsViewModels.kt:210-216`) | settings shows the error; alarms for that med stay cancelled | none |
| resume (user / event correction) | per `EventRecorded`, after commit (`SetMedicationStatusUseCase.kt:212-217`, `CorrectMedicationStatusEventUseCase.kt:92`) | log; next full reconcile | none |
| resume (auto-reconcile) | **new**: per med, after the cache write succeeds (`ReconcileMedicationStatusUseCase.kt:42-49`) | log; queue/startup | B7 removes the `if (derived)` gate and injects `Clock` into `pausedNow` (`AndroidReminderReconciler.kt:53-59` uses `System.currentTimeMillis()`) |
| import | `reconcileReminders.all(MEDICATION_CHANGED)` (`ImportPlanUseCase.kt:36`) | import is already committed; startup full reconcile | none |
| restore | `enqueue(DATA_RESTORED)` (`BackupRestoreUseCase.kt:104-106`) + process restart | startup full reconcile | B11 |
| startup recompute | resync performs its own `all(ROUTINE_CHANGED)` | — | B4 |

Rule: reconcile never runs inside a transaction (`AlarmManager`/`NotificationManager` cannot
participate); a failed reconcile never rolls back committed business state; every reconcile entry keeps
its existing `reason` value.

---

## 10. Protected asset — standing rule (binding on every batch)

> **The real application database is a protected asset.**
>
> 1. Every test and acceptance run **must** use an isolated database (in-memory, or a file owned by a
>    test-only package) and a **test APK**. The production package `com.driezy.medlog` and its data
>    directory are never part of a test run.
> 2. Without a **separate, explicit authorization for that specific run**, nobody may:
>    migrate, write to, or clear the real database — including "harmless" reads that open it with
>    `fallbackToDestructiveMigration`, schema-validation probes, and `pm clear`.
> 3. **Old backups must not be restored** (neither the app's own restore flow nor a file copy over
>    `medlog.db`). A pre-v27 backup re-runs migrations and can resurrect data that later phases have
>    deliberately left behind.
> 4. The app is not rebuilt, reinstalled, uninstalled or downgraded on the device during a batch.
> 5. If a batch's verification needs the production package, it stops and asks. "It only reads" is not
>    an authorization.
>
> Status of the asset on this device: the Termux uid `u0_a443` **cannot** read
> `/data/data/com.driezy.medlog/databases/` (`Permission denied`), so any access implies a privileged
> route (rish/Shizuku/root) — exactly the route that must never be taken by default.

Operational consequences for the acceptance commands (see TASKS §1): JVM unit tests only;
`:core:database` instrumentation under test package `com.driezy.medlog.core.database.test`; app-module
instrumentation only for tests that build their own in-memory Room database and a faked preferences
layer, never `DatabaseSchema.NAME`.

---

## 11. Decisions needed (do not invent scope — these are for the user)

| # | Question | Why it is a real decision | Recommendation |
|---|---|---|---|
| **Q1** | Batch order/priority: correctness-first (resync → editor gate → unarchive/resume → import → UI), or UI-visible-first (stepper + banner early so the feature is demoable)? | the correctness-first order has no user-visible change until B10; UI-first ships a UI that can create offsets before resync can preserve them | correctness-first (B1→B11 as listed); UI-first is only acceptable if B2 lands in the same release |
| **Q2** | Does the QR payload change (`off` field + "retimed" report) ship **in the same batch** as the import validation-before-delete fix? | the validation fix is a safety fix that can ship alone; the payload change is a **behaviour change** (R-16: old codes now get retimed) with its own UX | split: B8a = validate-before-delete (no payload change, shippable alone), B8b = `off` + retimed report |
| **Q3** | When a routine change conflicts, the batch rolls back — including for the **startup** recompute. One stored-illegal row (W4′/restore) therefore blocks convergence for *every other* med on that member, on every launch. Accept, or make the startup pass per-medication (skip + log)? | §3.6 says startup reuses the same write path; per-medication skipping there would be a second semantic (the batch-atomic rule of §3.5) — but the current form makes one bad row freeze the whole member | accept for Phase 2 (banner + editor are the fix path), revisit only if a real user hits it |
| **Q4** | Whole-DB restore: **(a)** trust the restored file as-is and converge (startup recompute + banner), or **(b)** validate the backup before copying (open read-only, scan `offsetMinutes`, refuse the restore with a named error)? | the spec is silent on restore; (b) needs the current routine + a cross-recipient scan and can refuse a restore the user explicitly asked for; (a) can leave illegal rows visible until edited | (a), documented, with the banner as the visibility guarantee; (b) only if restore must be provably invariant-preserving |
| **Q5** | Editor save-time rejection copy: when the data layer throws `IllegalTimeOffsetException` on save (reachable only via a stale draft after W4′), do we add a new string, or reuse `medication_save_failed` + the existing `time_offset_illegal_stored` hint? | §7 of the spec has no "save was rejected by the data layer" string; adding one is a copy decision (4 languages) | reuse `medication_save_failed` and put the reason from `TimeOffsetCheck.Rejected` in the error state of the stepper; no new key |
| **Q6** | Should a routine change be blocked (as §3.7 requires) by a conflict on a med that is **archived/paused** and therefore not in the resync set at all? | blocking is correct for "unarchive later would be refused", but it is a deadlock surface: a long-archived med with an offset can veto every future bedtime edit until the user edits *that* med | follow §3.7 (block, the scan includes archived) **but** the dialog must say the med is archived/paused; if the user wants otherwise, the alternative is "block only active + warn for archived" |
| **Q7** | Does Phase 2 cover the "restore" batch at all, or is it docs-only (a documented decision + the existing startup convergence)? | it is the one entry that can never be gated; a code change there would be a new feature (pre-copy scanning) | docs-only + one unit test that `gatedFor` still classifies a hand-written illegal row (that is what the banner reads) |
| **Q8** | `getAllMedicationsFor` (the pre-check) also returns **archived** rows; `planForRoutine` only touches active ones. Confirm that the pre-check's scope stays "all, including archived" (spec §3.7) rather than "same set as the resync"? | it is the difference between "block early" and "let the resync roll back", and it directly changes how often users get blocked | keep "all, including archived" (spec) — but see Q6 |

Not a question, recorded for the reviewer: imported rows start at `planEffectiveFromMs = 0L`, so the
first later edit writes a revision `[0, changedAt)`. `FuturePlanCalculator` clamps `lower` to
`maxOf(start, planEffectiveFromMs)` (`:58`), so this cannot project doses before the view window. This
is pre-existing behaviour and is **not** changed by the offset feature; it is recorded because the
offset feature now asserts INV-2 on imported rows.

---

## 12. Out of scope (explicitly, to keep batches honest)

Cross-midnight offsets (E1 / `next day` semantics), arbitrary minutes, per-slot offsets, offset in the
revision snapshot, per-care-task offsets, a global error centre, monotonic-clock handling of rollback,
cross-store transactions or locks, `MAX_REMINDER_SLOTS`, and any change to the projection / adherence /
`medication_logs` semantics. Also out of scope: making `materializedFor` reject non-applicable rows
(that would contradict the Phase 1 contract pinned by `MedicationMaterializationTest`; `gatedFor`
covers it instead).
