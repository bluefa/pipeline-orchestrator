# Decision 4 - One active pipeline per target

## Verdict

서비스 계층은 충족, trigger endpoint는 미구현. The implementation enforces one active
pipeline per target and duplicate creation returns the active run. However, there is no REST trigger
endpoint in `src/main`, so the endpoint part of the ADR contract is not implemented or tested.

## ADR requirements

- only one non-terminal pipeline per target.
- duplicate create, of any pipeline type, returns the existing active run instead of erroring.
- the trigger endpoint must honor this contract.
- once a pipeline terminalizes, the target can be used by a new pipeline.

## Evidence

- `pipeline.active_target` has a unique constraint:
  `src/main/java/com/bff/pipeline/entity/Pipeline.java:33`
- the class header explains this as the MySQL-compatible substitute for a partial unique index:
  `src/main/java/com/bff/pipeline/entity/Pipeline.java:22`
- creation inserts `status=RUNNING` and `activeTarget=target`:
  `src/main/java/com/bff/pipeline/service/PipelineInserter.java:42`
- duplicate insert handling catches only the active-target unique violation and returns
  `findByActiveTarget(target)`; it does not require the same pipeline type:
  `src/main/java/com/bff/pipeline/service/PipelineCreator.java:42`,
  `src/main/java/com/bff/pipeline/service/PipelineCreator.java:61`
- terminal transition clears `activeTarget`:
  `src/main/java/com/bff/pipeline/repository/PipelineRepository.java:27`
- both normal convergence and cancellation use `finish`:
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:77`,
  `src/main/java/com/bff/pipeline/service/PipelineControl.java:40`
- there is no trigger REST controller. The controller package currently contains only
  `GlobalAdvice`, whose header notes the module has no controller yet:
  `src/main/java/com/bff/pipeline/controller/GlobalAdvice.java:11`

## Gaps and risks

- endpoint contract is not implemented. Future REST code must call `PipelineCreator.create`, not
  `PipelineInserter.insert`, or it can accidentally turn duplicate create into an error.
- `active_target` is an application-maintained projection. The unique constraint enforces
  "at most one active target", but the DB alone does not enforce every consistency direction
  (`RUNNING -> active_target=target`, terminal -> `active_target=null`).
- concurrent duplicate create against a real MySQL unique violation path is not covered by a
  dedicated integration race test.

## Test coverage

- duplicate create for the same type returns the existing run:
  `src/test/java/com/bff/pipeline/service/PipelineUniquenessTest.java:48`
- duplicate create for a different type returns the existing run:
  `src/test/java/com/bff/pipeline/service/PipelineUniquenessTest.java:57`
- terminal pipeline releases target:
  `src/test/java/com/bff/pipeline/service/PipelineUniquenessTest.java:66`
- cancellation releases target:
  `src/test/java/com/bff/pipeline/service/PipelineControlTest.java:75`

## Conclusion

The domain/service invariant is implemented well, with a reasonable MySQL workaround. The ADR's
explicit trigger endpoint requirement remains unimplemented.
