# Tutorial 15 Ecommerce RAG Implementation Plan

> For agentic workers: implement tasks in this session, using independent parallel tasks only where file ownership and interfaces are disjoint. Every implementation task requires tests and review.

**Goal:** Finish tutorial 15 on the existing ecommerce app, including safe chunk uploads, BM25 + vector retrieval, RRF, Rerank, and independently configurable knowledge bases.

**Architecture:** Preserve the existing Spring Boot/Spring AI monolith and PostgreSQL. Add ES REST access through the existing OkHttp/Jackson stack, and a native Bailian Rerank client. Knowledge ownership is present from upload through both retrieval routes. A durable file-level index task table coordinates retryable ES synchronization after PostgreSQL commits.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring AI 2.0.1, PostgreSQL/pgvector, Elasticsearch/IK, Vue 3, OkHttp.

**Spec:** ../specs/2026-11-14-ecommerce-rag-upgrade-design.md

## Global Constraints

- Preserve all existing user changes, original database records, local files and secret configuration. No recursive/batch filesystem deletion commands. File cleanup uses known task-owned paths individually.
- Work on `codex/tutorial15-rag-20261114` in the existing checkout: the substantial uncommitted chapter 14 work is the implementation baseline. Do not reset or replace it with HEAD.
- Default knowledge base ID is 1, named 米兰数码商城. Missing IDs resolve to this default, never unrestricted retrieval. New knowledge bases use generated IDs.
- The final answer model and default general chat model are qwen3.8-flash. Embedding remains qwen3.7-text-embedding-flash / 1024 dimensions. Rerank is qwen3-rerank.
- Do not copy credentials, dedicated API hosts, or the paid tutorial text into published implementation files.
- Model connectivity is verified, but previous 60-question results are historical and must not be described as new hybrid-retrieval performance.

## Task 1: Knowledge ownership, upload lifecycle and migration

**Files:** `sql/schema.sql`, `sql/migrate_chunk_upload.sql`, new `sql/migrate_rag_knowledge_bases.sql`; file/chunk DOs and mappers; new knowledge-base DO/mapper/service/controller; upload request VOs and `CustomerServiceImpl`.

**Interfaces:** Knowledge CRUD is GET/POST `/customer-service/knowledge-bases`, PUT `/customer-service/knowledge-bases/{id}`, with `{name, description}` and standard Response envelopes. Default library record has ID 1. Existing file endpoints take `knowledgeBaseId`, default 1. Check/merge identify a task by `(knowledgeBaseId, fileMd5)`; metadata contains `knowledge_base_id` as a string and `mdStorageId` as the original numeric file ID.

- [x] Add failing tests for enum-safe deleted-owner checks, invalid MD5/indices, incomplete merge, hash/size mismatch, duplicate/idempotent merging, metadata/owner isolation and migration reruns.
- [x] Make legacy status conversion conditional on actual legacy table migration; preserve IDs, paths, and vectors. Add file/chunk ownership and composite uniqueness.
- [x] Lock file records before upload/merge/delete; create first records with conflict-safe inserts; verify bytes and MD5 before publishing an after-commit vectorization event.
- [x] Implement knowledge list/create/update and file ownership checks. Same content in different libraries remains independent.
- [x] Run focused unit tests and migration tests against a separate schema before touching existing data.

## Task 2: ES keyword service

**Files:** `config/RagProperties.java`, `service/EsChunkService.java`, `service/impl/EsChunkServiceImpl.java`, ES HTTP contract tests, Docker ES/IK configuration.

**Produces:** `ensureIndex()`, `upsert(List<Document>)`, `searchByKeyword(String,int,Long)`, `deleteByFileId(Long)`. ES IDs equal PG Document IDs. Fields include content, title, mdStorageId and knowledge_base_id; the latter two are keyword strings.

- [x] Write failing HTTP tests for index mapping, bulk errors, filtered BM25 searches and file deletion.
- [x] Implement REST calls using OkHttp and Jackson. Validate IDs and index settings; require a positive knowledge ID on searches.
- [x] Check deployed ES and IK versions match, bind to loopback, and verify real Chinese analysis/search.

## Task 3: Hybrid retrieval and Rerank

**Files:** RRF utility, hybrid/Rerank service implementations, their tests, `config/RagExecutorConfig.java`.

**Interfaces:** `HybridRetrievalService.search(String query,int topK,Long knowledgeBaseId)` returns owned candidates; `RerankService.rerank(String,List<Document>)` preserves IDs/metadata. `FileDocumentGuard.retainOwned(List<Document>,long)` is implemented by the coordinator. Retrieval defaults are 10 vector + 10 BM25, RRF k=60, fused pool 10, final TopN=3.

- [x] Test RRF duplicate handling, deterministic ties and reciprocal-rank scoring.
- [x] Test both route filters, ownership guard, and single-route fallback without querying another library.
- [x] Test native Rerank response indexes, malformed/duplicate results, timeouts and fallback order using a local HTTP server.
- [x] Implement configurable Rerank endpoint/key resolution from local OpenAI settings without exposing credentials in logs.

## Task 4: Durable index synchronization and RAG integration

**Files:** index task table/mapper/synchronizer, upload event listener, CustomerServiceAdvisor, customer-service controller/request VO.

- [x] Enqueue UPSERT in the same PostgreSQL transaction as vectorization, DELETE with file deletion; tasks have file ID, library ID, operation, retry count and last error.
- [x] Process tasks idempotently with row locks; preserve DELETE jobs after owner removal. Do not lock/update file rows while holding an index job lock, avoiding inverse lock ordering.
- [x] Reject keyword candidates whose file no longer exists or is not completed in the selected knowledge base.
- [x] Expose index synchronization state on file lists and provide a library-scoped reindex endpoint.
- [x] Wire hybrid → rerank → TopN into SSE. Use selected-library identity and context-supported channels/policies; keep the existing ecommerce guardrails.

## Task 5: Frontend and general chat model

**Files:** chat store and persisted-selection helper/test; knowledge API/store or component; CustomerServiceChatPage; MarkdownFileDrawer; customerService API.

- [x] Test migration of persisted obsolete default chat selection to qwen3.8-flash; preserve valid selectable alternatives.
- [x] Add library selection, create/edit entry, dynamic welcome, and cancellation/reset of active replies when switching libraries.
- [x] Include knowledgeBaseId on check/upload/merge/list/delete/update. Freeze upload ownership while requests are in flight.
- [x] Display index state; do not report failed knowledge parsing as successful searchable knowledge.
- [x] Run Node tests and Vite build.

## Task 6: Runtime, data migration and end-to-end verification

- [x] Start only this project's existing pgvector container and a new isolated ES service. Back up existing databases before migrations; never reset the knowledge corpus.
- [x] Run migration twice on isolated data and once on the actual demo database; compare pre/post file/vector counts and IDs.
- [x] Backfill ownership and queue ES synchronization from existing vectors, reusing embeddings.
- [x] Verify same-query conflicting policy isolation, same-file cross-library uploads, retry/delete recovery, and model SSE through the app.
- [x] Save a new 60-question run with full retrieved evidence and stages, separating retrieval from answer grading and failures from retries.
- [x] Review the final diff, run focused tests/backend package/frontend build, and update docs with measured behavior and remaining limits. No automatic GitHub push is included in this request.

## Progress and rulings

- Baseline: backend 13/14 focused tests pass; one hardcoded deleted-owner assertion uses the previous status enum. Frontend build passes.
- Model probes: Embedding 200 (1024 dims), qwen3-rerank 200, qwen3.8-flash SSE 200.
- Ruling: use an in-place feature branch rather than copying or discarding uncommitted chapter 14 changes. No worktree was requested.
- Ruling: use existing OkHttp/Jackson for ES REST, preserving the Spring Boot 4 dependency baseline while implementing the tutorial's ES/BM25 behavior.


- Completed 2026-11-15: all six implementation tracks are complete; chapter 14 chunk-upload work was accepted and retained.
- Fresh and in-place migrations were verified; `milan_ecommerce` retains its original 10 files/100 vectors, ES has 100 synced docs, and the outbox is empty. Original `robot` data remains separate.
- Both 60-question qwen3.8-flash runs were saved and graded with full evidence. Hybrid: 52 correct/8 partial, Top-3 retrieval 54/55. Vector baseline: 53 correct/7 partial, Top-3 retrieval 55/55. No accuracy improvement is claimed.
- Fresh verification passed: 143 backend tests and package build; 12 frontend tests and Vite build; 4 Python evaluation-runner tests; live SSE checks for both customer-service retrieval modes and general chat. Removed the single `新对话` row created by the existing Spring integration test from the demo DB.
- User-facing docs, evaluation reports, resume description, and workspace memory reflect the implementation and limits. No GitHub push or commit was performed.
