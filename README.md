# Spring Boot + Claude AI Chatbot

A production-oriented chatbot backend: Spring Boot, JWT auth, Postgres persistence,
Redis caching/rate-limiting, streaming responses, and a local Ollama model for chat.

---

## 1. Quick Start

Requires Postgres and Redis running (see `docker-compose.yml` — `docker compose up -d postgres redis`
is the easiest way) and a local [Ollama](https://ollama.com) server with a model pulled:

```bash
ollama serve
ollama pull llama3.2
```

```bash
export JWT_SECRET=$(openssl rand -base64 32)
mvn spring-boot:run
```

No API key is needed — chat runs entirely against your local Ollama server
(`http://localhost:11434` by default, see `OLLAMA_BASE_URL` in `.env`).

Then open `frontend/index.html` directly in a browser (or serve it with any static
file server) and register/login/chat.

Test the API directly:
```bash
# Register
curl -X POST localhost:8011/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"password123"}'
# -> {"token": "...", "tokenType": "Bearer", "expiresInSeconds": 86400}

# Chat
curl -X POST localhost:8011/api/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"message":"Hello, how are you?"}'
# -> {"conversationId": "...", "message": "I'm doing well...", ...}
```

---

## 2. Concepts (for developers new to LLM apps)

- **Chat API**: Ollama's `/api/chat` endpoint - you send a list of messages and get a
  generated reply back. There's no persistent "session" on the model server side -
  your app is responsible for storing and resending conversation history each time.
- **Prompt**: the text you send the model. A **system prompt** sets behavior/role
  ("You are a helpful support agent..."); **user**/**assistant** messages are the
  back-and-forth turns.
- **Context window**: the max tokens (input + output) a model call can process.
  Longer conversations must be trimmed or summarized to fit (see `ChatService.buildContext`).
- **Tokens**: the unit length is measured in - roughly ¾ of a word each. Running
  locally via Ollama, tokens don't cost money, but a longer context still costs
  latency/memory on whatever machine is running the model.
- **Temperature**: randomness/creativity of output (0 = deterministic, higher = more varied).
- **Streaming**: instead of waiting for the full reply, Ollama sends partial text
  chunks as they're generated - used here via SSE (`POST /api/chat/stream`).
- **Tool/function calling**: letting the model call functions you define (e.g. "look up
  order status") mid-conversation. Not implemented in this base project - see §8.
- **RAG (Retrieval-Augmented Generation)**: fetching relevant documents/data first,
  then including them in the prompt so Claude can answer using your private data.
- **Embeddings / vector databases**: representing text as numeric vectors so you can
  do semantic ("meaning-based") search — the retrieval half of RAG.

---

## 3. Architecture

```
Frontend (HTML/JS)
      |
Controller  (ChatController, AuthController)
      |
Service     (ChatService, AuthService)
      |
Claude layer (ClaudeService, PromptService)  <-- isolated, swappable
      |
Ollama HTTP API (POST /api/chat, local server)
```

Persistence: `ConversationRepository` / `MessageRepository` / `UserRepository` (JPA)
Caching/rate-limiting: `RedisConfig`, `RateLimiter`
Security: `JwtUtil`, `JwtAuthFilter`, `SecurityConfig`

The model-specific code lives entirely in `com.example.chatbot.claude` (named after
the class, `ClaudeService` - nothing outside that package talks to Ollama directly),
so you could swap providers by rewriting `ClaudeService` alone.

---

## 4. Database Schema

```
users (id, email UNIQUE, password_hash, created_at, updated_at)
conversations (id, user_id FK, title, created_at, updated_at)
messages (id, conversation_id FK, role[USER|ASSISTANT|SYSTEM], content, input_tokens, output_tokens, created_at)

-- RAG (postgres profile only, see §11)
documents (id, user_id FK, filename, status[PROCESSING|READY|FAILED], chunk_count, created_at)
document_chunks (id, document_id FK, user_id FK, content, chunk_index, embedding vector(1024), created_at)
```

One conversation → many messages. One user → many conversations. Every query is
scoped by `user_id` (see `ConversationRepository.findByIdAndUserId`) so one user can
never read another's data — verified in `ChatService` and enforced again at the
repository level, not just in the controller.

---

## 5. What's in Redis vs Postgres

| Data                              | Store    | Why |
|-----------------------------------|----------|-----|
| Users, conversations, messages    | Postgres | Durable source of truth |
| Rate-limit counters                | Redis    | High-frequency writes, auto-expiring |
| (Optional extension) hot conversation context cache | Redis | Skip a DB round-trip on the chat hot path |

Redis is treated as **disposable** — `GlobalExceptionHandler` returns a 503 rather
than corrupting state if Redis is briefly unavailable, and nothing here treats Redis
as the only copy of data that matters.

---

## 6. Authentication

- `POST /api/auth/register` / `POST /api/auth/login` return a JWT.
- Every other endpoint requires `Authorization: Bearer <token>`.
- Passwords are hashed with BCrypt (cost factor 12) — never stored or logged in plaintext.
- `JwtAuthFilter` validates the token on each request and loads the `User` fresh from
  the DB, so revoked/deleted users are rejected even with a still-valid token.

**Production checklist:**
- Set a real `JWT_SECRET` (32+ random bytes) via environment variable — never commit it.
- Reduce `JWT_EXPIRATION_MS` and add a refresh-token flow for anything long-lived.
- Restrict `SecurityConfig.corsConfigurationSource()` to your real frontend origin(s).

---

## 7. Streaming

`POST /api/chat/stream` returns Server-Sent Events:
```
event: token
data: Hello

event: token
data:  there

event: done
data: {"conversationId":"...","message":"Hello there","inputTokens":12,"outputTokens":4}
```
See `frontend/index.html` for a working consumer (fetch + manual SSE parsing, since
`EventSource` doesn't support custom headers like `Authorization`).

---

## 8. Context & Cost Management

- `ClaudeProperties.maxHistoryMessages` (default 20) caps how many past messages are
  resent to Claude on each turn — bounds both latency and cost as chats grow.
- **Input tokens** = system prompt + all resent history + new message. This is why
  unbounded history is the #1 silent cost driver in chatbots — every turn re-sends
  everything.
- **For longer-lived conversations**, replace the hard cutoff in
  `ChatService.buildContext` with a summarization step: periodically ask Claude to
  summarize older messages into a compact paragraph, store that as a synthetic
  "system" message, and drop the raw originals from the resent context.
- **Reducing calls**: cache identical/near-identical requests where relevant, batch
  non-interactive workloads via the Batches API instead of one-by-one calls, and
  avoid calling Claude at all for requests you can answer with static logic.
- **Model selection**: use a smaller/faster model for simple classification or
  routing tasks, and reserve larger models for the conversational replies that need it.
- Token usage per message is stored (`Message.inputTokens/outputTokens`) — sum these
  per user/conversation for real cost dashboards.

---

## 9. Testing

```bash
mvn test
```
- `ChatServiceTest` — mocks `ClaudeService` entirely, so **no local Ollama server is
  needed** and tests run offline/deterministically.
- `AuthControllerTest` — `@WebMvcTest` slice test with a mocked service layer.

Extend this with:
- A `ClaudeServiceTest` that mocks the `HttpClient` bean directly to verify error mapping
  (Ollama unreachable → 504, model not found → 404, etc.) without a real network call.
- `@SpringBootTest` + Testcontainers (Postgres + Redis) for full integration coverage.

---

## 10. Docker / Production Deployment

```bash
docker compose --env-file .env up --build
```

This starts the app + Postgres + Redis together, wired via environment variables only
— no secrets in source control. The app reaches your host machine's Ollama server via
`OLLAMA_BASE_URL=http://host.docker.internal:11434` (already the default in
`docker-compose.yml`) — make sure `ollama serve` is running on the host.

**Going to a real production server:**
1. Put the app behind a reverse proxy (nginx/Caddy/ALB) terminating HTTPS.
2. Run Postgres and Redis as managed services (RDS/Cloud SQL, ElastiCache/Memorystore)
   rather than in the same compose file, for durability and backups.
3. Inject secrets (`JWT_SECRET`, DB password, `VOYAGE_API_KEY` if RAG is on) via your
   platform's secret manager, not `.env` files, in real production. Point
   `OLLAMA_BASE_URL` at wherever your production Ollama (or Ollama-compatible) server runs.
4. Enable `management.endpoint.health.show-details: when-authorized` (already set) and
   point your monitoring at `/actuator/health` and `/actuator/metrics`.
5. Set log aggregation (e.g. to CloudWatch/Datadog) — logging config already avoids
   printing raw JWTs (see `JwtAuthFilter`, `ClaudeService`).

---

## 11. RAG (Retrieval-Augmented Generation) — implemented

Upload documents; the chatbot retrieves relevant excerpts and grounds its answers in
them, instead of relying only on Claude's training data.

**Why Voyage AI:** Anthropic does not offer its own embedding model and officially
recommends [Voyage AI](https://www.voyageai.com) as the pairing for Claude-based RAG.
`voyage-3-large` is the current strongest general-purpose model; use
`voyage-multilingual-2` for non-English content. Get a key at
https://dashboard.voyageai.com.

**Why pgvector:** keeps vectors next to your existing business data (one database,
one auth model, ordinary SQL joins), rather than standing up a separate vector store.
The `pgvector/pgvector` Docker image ships the extension pre-installed; most managed
Postgres providers (RDS 15.2+, Aurora, Supabase, Neon) support `CREATE EXTENSION vector`
directly.

### How it works

```
Upload:  file -> extract text (PDFBox for .pdf) -> chunk (1500 chars, 200 overlap)
              -> embed chunks in one batched Voyage call -> store in document_chunks

Chat:    user message -> embed the message (Voyage, input_type=query)
              -> pgvector cosine search, top-K, scoped to that user's documents
              -> relevant excerpts prepended to the system prompt
              -> sent to Claude alongside the normal conversation history
```

See `com.example.chatbot.rag`:
- `VoyageEmbeddingClient` — isolated embeddings client, same pattern as `ClaudeService`
- `DocumentChunker` — text extraction + sliding-window chunking
- `DocumentIngestionService` — upload -> chunk -> embed -> store pipeline
- `VectorSearchRepository` — raw JDBC for the `vector(1024)` column and `<=>` cosine search
  (deliberately not a JPA field mapping — see the class comment for why)
- `RetrievalService` — embeds the query and builds the context block; `ChatService`
  calls this once per turn when RAG is enabled

### Enabling it

RAG requires the pgvector extension, which the `postgres` service in `docker-compose.yml`
already provides:

```bash
export RAG_ENABLED=true
export VOYAGE_API_KEY=pa-your-key-here
export DB_PASSWORD=...   # etc, see .env
docker compose up -d postgres redis   # or run Postgres yourself with pgvector
mvn spring-boot:run
```

Or via `docker compose up --build` with `RAG_ENABLED=true` and `VOYAGE_API_KEY` set
in your `.env` — `docker-compose.yml` already uses the `pgvector/pgvector:pg16` image.

```bash
# Upload a document
curl -X POST localhost:8011/api/documents \
  -H "Authorization: Bearer <token>" \
  -F "file=@handbook.pdf"

# Chat - relevant excerpts are now retrieved and injected automatically
curl -X POST localhost:8011/api/chat \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"message":"What does the handbook say about PTO?"}'
```

### Production notes

- **Chunking**: the current character-based sliding window (`RAG_CHUNK_SIZE=1500`,
  `RAG_CHUNK_OVERLAP=200`) is a reasonable default. For structured documents
  (contracts, code, markdown with headers), consider chunking on natural boundaries
  (paragraphs, sections, functions) instead of a fixed character count.
- **Ingestion is synchronous** in `DocumentIngestionService.ingest()` for simplicity.
  For large files or high upload volume, move it behind `@Async` or a real job queue
  and let the client poll `Document.status` (`PROCESSING` → `READY`/`FAILED`), which
  the schema already supports.
- **Index tuning**: the `ivfflat` index (`V2__rag_pgvector.sql`) needs data to train
  well and its `lists` parameter should scale roughly with `sqrt(row_count)` — revisit
  once your corpus is real-sized. For small corpora, an exact scan (no index) is fine.
- **Reranking**: for higher retrieval precision at added cost/latency, add a reranking
  pass (e.g. `voyage-rerank-2` or a Claude Haiku rerank call) between the top-K vector
  search and what actually gets injected into the prompt.
- **Cost**: embeddings are billed separately from Claude generation — batch embedding
  calls (already done here, one call per document) rather than one call per chunk.

---

## 12. Other Advanced Features (roadmap, not yet implemented here)

- **Tool/function calling**: define tool schemas and pass them via
  `MessageCreateParams.builder().tools(...)`; when Claude requests a tool call, execute
  it server-side (e.g. call your own Spring Boot service/API) and send the result back
  as a `tool_result` message before getting the final reply.
- **Database query / ERP assistant**: a specific case of tool calling — expose a
  read-only, parameterized query tool rather than letting the model write raw SQL.
- **Role-based access**: extend `User` with a `role` field and `@PreAuthorize` checks;
  the JWT already carries the user id, so claims-based authorization is a small add.
- **Multi-tenant**: add an `organization_id` column across `users`/`conversations`,
  scope every repository query by it the same way `user_id` is scoped now.
- **Conversation summarization**: see §8.

---

## 13. Project Structure

```
com.example.chatbot
├── controller      # AuthController, ChatController, DocumentController
├── service         # ChatService, AuthService
├── claude          # ClaudeConfig, ClaudeProperties, ClaudeService, PromptService
├── rag             # VoyageEmbeddingClient, DocumentChunker, DocumentIngestionService,
│                   # VectorSearchRepository, RetrievalService, RagProperties
├── entity          # User, Conversation, Message, Role, Document, DocumentChunk
├── repository      # UserRepository, ConversationRepository, MessageRepository,
│                   # DocumentRepository, DocumentChunkRepository
├── dto             # ChatDtos, AuthDtos, DocumentDtos
├── security        # JwtUtil, JwtAuthFilter, AppUserPrincipal, AppUserDetailsService
├── config          # SecurityConfig, RedisConfig, RateLimiter
└── exception       # ClaudeApiException, AppExceptions, GlobalExceptionHandler
```

---

## 14. Environment Variables Reference

See `.env` for the full list. The only **required** one to get running is `JWT_SECRET`
— chat needs a local Ollama server reachable at `OLLAMA_BASE_URL` (defaults to
`http://localhost:11434`), but no API key.
