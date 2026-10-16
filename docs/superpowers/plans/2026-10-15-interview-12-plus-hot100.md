# Interview 12 + 1 implementation plan

## Requirements and global constraints

- Product flow is fixed: 12 main interview questions, then one algorithm question selected from the official LeetCode Hot 100 catalog. Follow-ups do not consume the 13-question quota.
- Follow-ups attach to the current main question. The model may request a follow-up when useful; the server limits this to two per main question, prohibits follow-ups after the algorithm question, and determines the actual ordinal and parent ID. Never trust model-supplied IDs or weights.
- Algorithm selection is server-owned and frozen for a session before it is asked. Use a checked-in catalog of Hot 100 question IDs, titles, slugs and difficulty with an official source URL; display the selected title/link. No runtime dependency on LeetCode availability. Do not copy the entire official question statement corpus.
- The algorithm question has a usable self-contained description, examples and boundaries (an original concise restatement is acceptable), and asks for approach, code or pseudocode, complexity and edge cases. The selected official problem identity must remain fixed; no code execution is required.
- Algorithm weight is always 20%. Main question weights retain the existing importance/difficulty policy and share 80%, using stable two-decimal percentages. Follow-ups have no independent weight and are assessed together with their main answer against that main question's frozen rubric.
- No comprehensive score until all 13 root questions are answered and assessed. Early completion still produces honest per-question feedback and explicitly states the omitted algorithm/main questions; do not scale the missing algorithm's 20% into the main section.
- Preserve pending-answer retries, idempotency, ownership checks, report caching and retries, immutable pre-question criteria, evidence validation, and null versus zero semantics.
- Reports count root questions only and nest follow-up exchanges under the parent; algorithm metadata and fixed weight are visible in both session and report.
- Keep project-local skill files authoritative and preserve upstream originals. Preserve unrelated dirty Settings/speech changes. Work on branch codex/interview-12-plus-hot100; do not commit unrelated changes or push this new work without a new request.

## Task 1 — Backend flow, catalog, scoring and skill integration

Implement the requirements above across interview service, agent, controller, report assembler, model records and project skill references. Prefer backwards-compatible constructors/helpers where useful for existing tests, but public product starts always use the fixed 12+1 configuration (legacy questionCount input cannot silently override it). A separate service overload may retain configurable small sessions only if this is an actual domain capability with honest algorithm semantics, not a test-only production switch; otherwise migrate tests to fixed sessions.

Session questionMetadata entries must expose kind MAIN/FOLLOW_UP/ALGORITHM, parentQuestionId (follow-up only), mainQuestionIndex (1..12 main, 13 algorithm, inherited for follow-ups), and algorithm {id,title,url,difficulty} on the algorithm question. Existing fields remain. questionCount is 13. Report turnFeedbacks contain only main and algorithm roots, with the same kind/index/algorithm metadata and followUps containing canonical {questionId,question,answer} exchanges. Existing report counts count roots. Main weights sum to 80.00 across frozen main contracts, including an issued unanswered root on early stop; the algorithm row is 20.00. Partial reports explicitly explain the incomplete scope and have no overall score.

Agent scoring batches are bounded (at most five root groups per model call, with the existing 8192 budget), preserve earlier batch successes if a later call fails, and retry only missing groups. Candidate evidence may come from the main answer or one of its own follow-up answers, never an unrelated answer or an artificial concatenation spanning answer boundaries.

Write meaningful failing tests before implementation: full 12+1 flow, follow-up cap/count/parent and retry, real catalog membership and frozen selection, fixed 20/80 arithmetic, early-stop algorithm absence, grouped evidence acceptance/rejection, 13-question report batching and partial retry, API fixed defaults. Run backend tests and build. Update the project skill local references and README documentation to the final behavior. Write a task report at /private/tmp/interview-12-backend-report.md with files, contracts, test commands/results, concerns. Do not commit; the controller will package/review changes with the existing dirty work preserved.

## Task 2 — Frontend and end-to-end verification

Replace the adjustable question-count input with 12+1 explanation (main80%, algorithm20%, follow-ups excluded). Display root progress and distinct main/follow-up/algorithm labels using server metadata; show the selected official algorithm link and support multiline code/pseudocode in the existing answer input. Restore and speech flows must retain their behavior.

Report display nests follow-ups, shows the Hot100 source and fixed algorithm share, uses root-only counts, and does not imply an early-stop report has a full total. Update frontend tests and browser harness with a full 12+1 fixture plus follow-ups, partial reports and fixed weighting. Run Node tests, build, and browser checks as available. Inspect changes and request final review; resolve important findings and rerun the covering tests before completion.

## Progress

- Backend complete: fixed 13 roots, server-owned follow-up parents/cap, frozen Hot 100 identity, exact 80/20 arithmetic, grouped assessment and five-group batching. Legacy tests, protocol guards, local skill references and README updated. Maven clean package passed on 2026-10-16 with 191 tests, zero failures/errors/skips, and BUILD SUCCESS. Final test formatting correction was verified with all 11 InterviewTwelvePlusOneTest cases passing. Packaged catalog and six unchanged upstream hashes independently checked.
- Frontend complete; 111 Node tests and production build passed on 2026-10-16. Chrome acceptance passed all 49 checks, including full 12+1 plus follow-up, fixed weights, partial retry, speech and PDF export. Shared official-link helper is used by both views; active, just-completed and restored completed algorithm questions retain their source links. Final browser artifacts: `/var/folders/qs/5st8bn617_9bcmqhspc9m1n00000gn/T/interview-browser-bpvOic/artifacts`.
- Final backend/frontend review approved with no outstanding Critical, Important or Minor findings. `git diff --check HEAD` passed. Detailed review and test evidence recorded in `/private/tmp/interview-12-final-review.md`, `/private/tmp/interview-12-backend-report.md` and `/private/tmp/interview-12-frontend-fix-report.md`.
- The user authorized committing and merging this feature to remote main. The integration snapshot excludes unrelated Settings/speech edits, which remain in the original workspace.

Validation uses deterministic model/API fixtures and fake audio devices; live-model algorithm restatement fidelity and scoring quality were not evaluated. Maven exited successfully but its full runs emitted a Surefire shutdown warning: the captured thread dump shows Tomcat acceptor shutdown waiting beyond the fork exit window. No unrelated shutdown changes were made; this did not cause a test failure.

Integration verification for remote main (2026-10-16): the isolated feature-only snapshot passed 182 backend tests and Maven package, 110 frontend tests and production build, and 49 Chrome acceptance checks. Counts exclude the separate staged Settings/speech work. The selective Interview.vue projection preserving main's inline model settings was reviewed and approved.
