# Text Interview Implementation Plan

**Goal:** Upload a resume and conduct a resumable, AI-driven text interview.
**Architecture:** Vue calls Spring REST endpoints; the backend extracts PDF/DOCX/TXT text and keeps browser-session-scoped materials and interview history in memory. Reuse AiClientResolver; no fake interview fallback.
**Tech stack:** Vue 3, Spring Boot, Java 17, PDFBox, Apache POI, JUnit/MockMvc.

## Contract
All responses use ApiResponse {success,message,data}. Browser sends credentials with requests; backend uses HttpSession identity (separate from demo login). AI headers are optional X-AI-Api-Key and X-AI-Model; server env provides defaults. Secrets are never persisted by UI.
- POST /api/materials multipart file => {materialId,fileName,status:"READY",text}
- GET /api/materials/{id} => same, owner-scoped
- POST /api/interviews {materialId,targetRole,questionCount:5} => session
- GET /api/interviews/{id} => session
- POST /api/interviews/{id}/turns {questionId,answer} => session
- POST /api/interviews/{id}/finish => session
Session: {sessionId,materialId,targetRole,questionCount,status:"ACTIVE"|"FINISHED",turns:[{questionId,question,answer:null|string}],currentQuestion:{questionId,question,answer:null}|null,pendingAnswer:null|string}
On model failure preserve pending answer and current question; retry same question safely. Identical successful retries return snapshot without generating twice; conflicting answer gives 409. Final answer ends automatically. GET never calls AI.

## Tasks
- [x] Backend tests first: parsing real TXT/PDF/DOCX fixtures, invalid uploads, missing model, history-based questions, final/early finish, retry failure, duplicate submission, cross-session access.
- [x] Implement MaterialService, InterviewService, InterviewAgent, REST controller + scoped errors. Limit uploads 5MB, PDF 30 pages, text 20000 chars, answers 5000 chars, questions 1–10. One day expiry; bounded per-browser storage. Use per-session locking, no global model lock.
- [ ] Frontend: API client + tests, replace Interview.vue with upload/preview/model configuration/start/chat/retry/finish flow. Store only interview ID in sessionStorage; recover on refresh. Include paste-text fallback uploaded as TXT. Vite proxy /api to backend; configurable API base for production.
- [ ] Integration: Maven tests, old job offline assertions, frontend build, browser/HTTP smoke with controlled model adapter. Document API and real model setup; distinguish test model from live model verification.

## Verification ledger
- Backend: 13 Maven tests passed, including actual HTTP multipart limits and local OpenAI-compatible transport.
- Existing job module: 102 offline assertions passed.
- Review: server-key endpoint boundary and pending-answer conflict fixed with regression tests; re-review approved.
- No live provider credentials used; genuine provider connectivity remains a configuration-time check.
