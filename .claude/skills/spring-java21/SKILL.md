---
name: spring-java21
description: Engineering harness for Spring Boot 3 / Java 21 backend work in this repo (test-spring/ and any future Java module). Use when writing, reviewing, or testing Java/Spring code. Encodes Spring Boot 3 + Java 21 idioms, JPA/transaction rules, a no-@Transactional-in-tests testing policy, and an ENFORCED Clean Code rule set with a review checklist. Pair with anti-patterns and coding-standards.
---

# Spring Boot 3 / Java 21 — Implementation & Review Harness

This is the standard for the `test-spring/` module (ADR-016 reference impl) and any Java/Spring code. It is a **rule set**, not a tutorial. When code violates a MUST rule, fix it; when it violates a SHOULD rule, fix it unless there is a stated reason. Reviews (human, codex, opus) apply §6 as the checklist.

> Hard repo rules still win: `AGENTS.md`, `CLAUDE.md`, the relevant ADR. This skill governs *how Java/Spring code is written*, not *what to build*.

---

## 1. Java 21 idioms

- **Records for immutable data** — DTOs, value objects, outcome types, contexts. Put validation in the compact constructor. Never add mutable state to a record.
- **Sealed interfaces for closed hierarchies** — model "one of N outcomes" as a `sealed interface` with `record` variants, consumed by an exhaustive `switch` with pattern matching (no `default` when the set is closed, so a new variant is a compile error). Example: `DispatchOutcome` = Accepted | Rejected | Backpressure | CallTimeout.
- **Exhaustive switch + pattern matching** over `instanceof` chains. Prefer arrow form `case X x -> ...`.
- **`Optional`** for "maybe absent" return values; never for fields or parameters. Never `Optional.get()` without a preceding presence check — use `orElseThrow`, `map`, `ifPresent`.
- **`var`** only when the initializer makes the type obvious. Never `var` for a literal or an ambiguous call.
- **Immutability first**: `final` fields, `List.copyOf(...)` for stored collections, no setters unless the object is a JPA entity that genuinely mutates.
- **No raw types**, no `null` returns for collections (return empty), no checked-exception leakage where a domain exception is clearer.
- **Virtual Threads** are the production mechanism for non-blocking async fan-out (`Executors.newVirtualThreadPerTaskExecutor()`), but watch carrier pinning (`synchronized` blocking I/O). Behind an interface so tests can inject a synchronous executor.

## 2. Spring Boot 3 idioms

- **Constructor injection ONLY.** No field/`@Autowired`-field injection, no setter injection for required deps. A single constructor needs no `@Autowired`. This makes deps `final`, the object testable without Spring, and missing deps a compile/startup error.
- **Stereotypes**: `@Service` (logic), `@Repository` (Spring Data interface), `@Component` (other beans), `@Configuration` + `@Bean` (wiring). One responsibility per bean.
- **`@ConfigurationProperties`** for grouped settings (not scattered `@Value`). Bind to a typed class.
- **No business logic in entities or in controllers.** Entities are state; controllers (when present) are thin adapters; logic lives in services.
- **Package by feature/layer of the module**, not by tech only. Keep `domain`, `repo`, `service`, `handler`, `reconciler`, `config` cohesive.
- **Inject `java.time.Clock`** (a `@Bean`) for any time-dependent logic; never call `Instant.now()`/`System.currentTimeMillis()` directly in services. This is non-negotiable for deterministic tests.

## 3. Persistence / JPA

- **Entities**: `@Getter @Setter @NoArgsConstructor` (Lombok) — **never `@Data` on entities** (its `equals`/`hashCode`/`toString` over all fields breaks with lazy proxies and identity). Keep `equals`/`hashCode` default (identity) unless you have a business key.
- **`@Version`** gives optimistic-lock (lost-update) protection. It is NOT the same as a status-predicated compare-and-set: when the rule is "transition only from an expected prior state" (CAS), use an explicit guarded update:
  ```java
  @Modifying
  @Query("update Pipeline p set p.status=:to, p.lastActivityAt=:now, p.version=p.version+1 " +
         "where p.id=:id and p.status=:expected")
  int cas(Long id, PipelineStatus expected, PipelineStatus to, Instant now);  // 0 rows = stale/illegal = no-op
  ```
  A 0-row result is the no-op (terminal revival / lost write blocked) — handle it, don't assume success.
- **No N+1**: fetch what you read; avoid lazy access outside a session; prefer explicit queries to entity-graph surprises.
- Keep repository methods intention-named (`findFirstByTargetSourceIdAndStatusInOrderByStartedAtDesc`). Push set-based work to queries, not loops.
- jsonb / DB-specific features: isolate them; document Postgres-only behavior (partial unique, `SKIP LOCKED`) where H2 cannot reproduce it.

## 4. Testing

- **DO NOT annotate tests with `@Transactional`.** A test-wrapping transaction hides real propagation (a method with `REQUIRES_NEW` won't actually get a new physical tx; auto-rollback masks commit/visibility bugs) and produces false negatives. Instead: let production code own its tx boundaries, and **clean state between tests** (truncate / `deleteAll` in `@BeforeEach`, or `@DataJpaTest` only for pure repository tests where its rollback is acceptable and propagation is not under test).
- **Test the behavior, not the implementation.** One behavior per `@Test`; name it after the behavior (`dispatchedTaskBecomesRunningOnNextTick`, not `testTick2`). Given/When/Then structure.
- **AssertJ** (`assertThat(...)`) for fluent, readable assertions. Assert the *observable outcome*, not internal calls, where possible.
- **Fakes over mocks for boundaries** (a scripted `FakeTerraformJobHandler`/`FakeImClient` reads better than deep Mockito stubbing for stateful flows). Use Mockito for simple collaborators.
- **Inject a fixed `Clock`** (`Clock.fixed(...)`) and advance it explicitly to test timeouts/TTL/cadence deterministically.
- Each non-trivial branch (a guard, a loop, a state transition, a money/security/correctness path) needs at least one test. The fragile interaction effects (CAS guards, ordering, accounting) need explicit tests, not just happy-path coverage.

## 5. Clean Code — ENFORCED RULES

These are the rules most often violated. Treat each as a review gate.

1. **Intention-revealing names.** Classes = nouns (`PipelineCreationService`); methods = verbs (`resolveRecipe`, `derivePipelineStatus`); booleans read as predicates (`isTerminal`, `occupiesSlot`). No abbreviations except universally-known ones. A name that needs a comment to explain it is the wrong name.
2. **Small methods, one thing.** Target ≤ 15 lines; > 30 lines is a defect — extract well-named helpers. A method does one level of abstraction. If you must scroll, split.
3. **Single Responsibility.** A class has one reason to change. The reconciler tick orchestrates; per-transition logic, observation writing, and derivation are separate units — do not fuse them into a 300-line method.
4. **No magic numbers / strings.** Name them (`DEFAULT_MAX_FAIL_COUNT`, `HANDLER_RESOLVE_OP = "orchestrator.handler.resolve"`). Settings come from `PipelineSettings`, not inline literals.
5. **Guard clauses, shallow nesting.** Return/continue early; never nest beyond ~2 levels. Replace `if (x) { ... long body ... }` with `if (!x) return;`.
6. **DRY.** Duplicated transition/observation/accounting logic is a bug waiting to diverge. Extract one source of truth (e.g., one `recordObservation` with RLE, one `failAttempt`).
7. **Meaningful error handling.** Throw specific exceptions (`UnknownHandlerException`), never swallow (`catch (Exception e) {}`), never `catch` and `return null`. Catch only what you can handle; let the rest propagate. The `23505 → return existing` catch is targeted (catch `DataIntegrityViolationException`, verify it is the unique violation, then act).
8. **Immutability & CQS.** Prefer immutable inputs; a method either does something (command) or answers something (query), not both. No hidden mutation in a getter.
9. **Comment WHY, not WHAT.** Code says what; comments explain non-obvious intent, an ADR rule, or a subtle invariant (e.g., "// CAS: 0 rows means the task already terminal — late response dropped"). Delete comments that restate the code.
10. **No dead code, no speculative generality.** No unused params, no "for later" abstractions, no interface with one impl unless it is a real boundary (IM client, Leader). YAGNI.
11. **Short parameter lists** (≤ 3–4). Group related params into a record/context object. Avoid bare boolean flags — either split the method or pass a named enum.
12. **No static mutable state.** No singletons holding mutable maps; state lives in the DB or injected beans.
13. **Fail fast.** Validate inputs at the boundary; `Objects.requireNonNull`, explicit precondition checks, `IllegalArgumentException` with a message.

## 6. Review checklist (apply to every change)

- [ ] Constructor injection only; deps `final`; no field injection.
- [ ] `Clock` injected; no direct `now()` in logic.
- [ ] CAS/guarded transitions are explicit prior-state `@Modifying` updates, 0-row handled — NOT `@Version` alone.
- [ ] tx boundaries correct: state/accounting on the owning tx; independent observation writes use `REQUIRES_NEW` on a *separate bean* (self-invocation does not trigger the proxy).
- [ ] No method > ~30 lines / no class doing > 1 job / nesting ≤ 2.
- [ ] No magic literals; names intention-revealing; no abbreviations.
- [ ] Sealed/exhaustive switch for closed outcome sets; no `default` swallow.
- [ ] Exceptions specific and targeted; no empty catch / null-return-on-error.
- [ ] Tests: no `@Transactional` on tests; behavior-named; fixed Clock; each fragile branch tested; fakes for boundaries.
- [ ] No dead code, unused params, or speculative abstractions.
- [ ] Matches the surrounding module's style and the ADR's stated invariants.

## 7. ADR-016 module conventions (test-spring/)

- **Single writer**: state transitions + fail_count + attempt lifecycle boundaries + `pipeline_event` are written by the reconciler tick tx ONLY. Observations (`task_check`), `attempt.response`, and backpressure `next_check_at` are written by the call-thread component in a `REQUIRES_NEW` tx. Keep these on separate beans so the proxy honors propagation.
- **Guarded CAS**, not `@Version`-alone, for: response adoption (`response IS NULL AND finished_at IS NULL AND status=DISPATCHING`), cancel (`prior=RUNNING`), and every status transition.
- **Boundaries are interfaces**: `PipelineHandler` (IM), `Leader` (advisory lock), `ExternalCallExecutor` (async). Correctness must not depend on the Leader (assume split-brain; CAS + idempotency carry it).
- **Determinism**: inject `Clock`; tests advance it; the test executor is synchronous.

## References (web, retrieved 2026-06)

- Spring docs — Beans & Dependency Injection (constructor injection): https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html
- Java 21 features (records, sealed, virtual threads): https://tech-champion.com/programming/java/new-features-in-java-21-records-virtual-threads-sealed-classes-more-explained/
- Spring Boot + Virtual Threads: https://www.javacodegeeks.com/2025/04/spring-boot-performance-with-java-virtual-threads.html
- Clean Code in Java (Baeldung): https://www.baeldung.com/java-clean-code
- Clean code best practices (JAVAPRO): https://javapro.io/2025/11/25/best-practices-for-writing-clean-code-in-java/
- Don't use @Transactional in tests: https://dev.to/henrykeys/don-t-use-transactional-in-tests-40eb
- Spring Framework — test transaction management: https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/tx.html
