# Role-specific interview specialty implementation plan

> For agentic workers: use subagent-driven-development for implementation and review. Preserve existing Settings/speech work and integrate only this feature into remote main after verification, as authorized by the user.

**Goal:** Keep 12 main questions plus one 20% specialty question; use Hot100 for software/coding roles and a relevant professional case for other roles.

**Architecture:** The opening model response supplies both the first frozen question and a frozen specialty plan. The server validates and stores that plan before presenting the first question. At question 13 it generates either the selected Hot100 problem or a self-contained case against that plan; arithmetic and root counts treat both as the same 20% component. No extra model request is needed for planning.

**Tech stack:** Java17/Spring Boot/Jackson, Vue3, Node tests and the existing local Chrome harness.

## Global constraints

- Fixed 12 main questions plus one specialty, total13. Main weights share80%, specialty fixed20%. Follow-ups retain their parent rubric, have no independent weight and do not consume the quota; existing max2 per main remains.
- Route by the target job, never solely by major or the presence of coding tools on the résumé. An explicit non-coding target job takes precedence over software education/history. If no target job is supplied, infer the main professional direction from résumé duties; if unclear, choose CASE_STUDY. Software development/algorithm roles suitable for coding assessment use ALGORITHM/Hot100. Product, operations, marketing, finance, HR and similar roles use relevant professional cases.
- A frozen plan is `{kind:"ALGORITHM"|"CASE_STUDY",focus:string,reason:string}` with nonempty bounded text. Expose it as `Session.specialty` from the first question for a clear rationale. A malformed plan fails opening and is retryable; do not silently assign Hot100. Later answers or skill edits cannot reroute an active session.
- Add CASE_STUDY to question metadata kind; MAIN/FOLLOW_UP/ALGORITHM remain compatible. Algorithms retain canonical catalog metadata and frozen identity. Case rows have no fabricated algorithm title, ID, source link or Hot100 label. Both terminal kinds finish after answer13 and receive exactly20%.
- Cases must contain an original self-contained role-specific scenario, supplied facts/data, explicit task and boundaries. Candidates can answer using professional analysis, methods and tradeoffs; do not require code or invent their past experiences. Generate and freeze five role-relevant criteria before showing the case. Reuse the existing evidence validation and batched assessment/retry.
- Overall only after all13 roots are answered and assessed; early-stop scope says "专项题" and preserves20% without reallocation. Ordinary roots remain12. Use unrounded raw units for main arithmetic and final one-step rounding.
- Keep project-local skill files authoritative, update only local extensions and documentation, and preserve all six upstream original hashes.
- Preserve all existing dirty/staged Settings and speech files and their staged state. Work on codex/role-specific-interview-specialty from903636a. User authorized completing, committing and merging this correction to remote main. Integration uses a clean feature-only snapshot and retains main's inline settings UI separately from the user's local Settings edits.

## Task1 — Backend planning, case generation, scoring, tests and local skill

Files: interview/InterviewAgent.java, InterviewService.java, InterviewReportAssembler.java, InterviewScoringPolicy.java; add a focused InterviewSpecialty.java value object as needed. Adjust associated fixtures/tests and add InterviewSpecialtyTest.java. Update skills/interview/README.md and its two project-* references plus README.md, backend/README.md and backend/docs/文字面试接口与接入.md. Avoid unrelated speech/config files.

- [x] RED: service opening exposes CASE_STUDY for an operations target despite CS experience; ALGORITHM for Java developer; blank/unknown role uses inferred direction or safe case plan; malformed/unknown plan is rejected before issuing a session. Tests inspect model instructions/data so fixture decisions are grounded in the routing contract, while acknowledging live-model judgment is not proven.
- [x] Extend the opening JSON contract with `specialty` alongside the normal first question. A new prepareOpening method can share the existing question parser with prepareNext; do not add an extra model request. Keep ordinary prepareQuestion callers compatible. Update fixtures to include a valid opening plan and preserve prior tests' meaning.
- [x] State stores frozen plan; select Hot100 only for ALGORITHM plans. Introduce CASE_STUDY metadata and self-contained case generation with an explicit SYSTEM mode that overrides normal Grill response-format rules. Preserve malformed-response, auth failure and pending-answer behavior.
- [x] Generalize the terminal dispatch and 20% predicate in progression, share allocation, overall arithmetic and report scope. No changes to grouping/caching/ownership/idempotency policy.
- [x] GREEN tests cover full13 CASE_STUDY and ALGORITHM flows, exact80/20 and zero/null semantics, early stop, frozen plan and pending retry, no Hot100 metadata for cases, non-coding answer instructions/criteria, report batching and caching. Reject special types before13. Run focused tests during iteration, full backend suite and package once at completion.
- [x] Update local skill protocols and docs to match final serialized contract and limitations. Verify six upstream hashes unchanged. Write /private/tmp/interview-specialty-backend-report.md with RED/GREEN commands/results and exact API decisions. Do not stage, commit or format unrelated code.

## Task2 — Frontend, browser coverage and final integration

Files: frontend/src/views/Interview.vue, Report.vue, frontend/src/interview/ shared question presentation helper if useful; new/updated API and presentation tests; frontend/tools/check-interview.mjs.

- [x] RED tests for operations plan/case labels, generic setup12+1 copy, no algorithm code hint/link on a case, root progress and fixed20. Keep technical Hot100 source and multiline code behavior.
- [x] Setup says "12 道主问题 + 1 道岗位专项题" and explains role matching80/20. After start show the frozen plan type/focus/reason. Active/history/report cases say "专业案例题" or "岗位专项题" and show20%, with professional answer guidance. Technical plans retain Hot100 and source links. Both kinds preserve13-root progress, nested follow-ups and truthful early reports.
- [x] Add full non-tech13-root browser fixture plus existing technical fixture, partial retry/early stop, safe plain-text case rendering and completed/restored case history. Run Node tests, build and Chrome checks. Review task changes and resolve important findings.
- [x] Build a feature-only snapshot excluding unrelated user changes, verify exact commit contents, commit and fast-forward remote main; confirm remote SHA and preserve local dirty/staged files. Record completion and final evidence here.

## Progress

- Implementation and independent review complete. Exact feature snapshot validated; integration to main follows.

Integration verification for remote main (2026-10-16): the isolated feature-only snapshot passed 190 backend tests and Maven package, 112 frontend tests and production build, and 56 Chrome acceptance checks. Counts exclude the separate staged Settings/speech work. The selective Interview.vue projection preserving main's inline model settings was reviewed and approved.
