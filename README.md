# SEEK — NL2SQL RAG Pipeline

> **Natural Language → SQL** powered by **RAG + OpenAI GPT-4o + Pinecone + Redis**

SEEK lets you type a plain-English question like _"list all active employees with salary > 50000"_ and instantly get a validated, executable SQL query — with confidence scoring, PII masking, semantic caching, and live result execution.

---

## Architecture

```
User NL Query
      │
      ▼
┌─────────────────────────────────────────────────────┐
│  Spring Boot 3 Backend  (port 9095)                 │
│                                                     │
│  PII Sanitization  →  Redis Semantic Cache          │
│        │                      │                     │
│        ▼               Cache HIT → return           │
│  Embed Query (OpenAI)                               │
│        │                                            │
│        ▼                                            │
│  Vector Search (Pinecone / In-memory)               │
│        │  Top-K schema chunks                       │
│        ▼                                            │
│  LLM Prompt (GPT-4o)  →  SQL                        │
│        │                                            │
│        ▼                                            │
│  Validate SQL  →  Confidence Score  →  Explain      │
│        │                                            │
│        ▼                                            │
│  Execute SQL (optional)  →  Live Results            │
└─────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────┐
│  Next.js UI  (port 3000)                            │
│  Query Page · Cache Admin · Normalize Checker       │
└─────────────────────────────────────────────────────┘
```

---

## Features

| Feature | Description |
|---|---|
| **NL → SQL** | Converts natural language to validated SQL using RAG + GPT-4o |
| **Two-tier semantic cache** | NORM (SHA-256 of normalized query) + VEC (cosine similarity) backed by Redis |
| **PII / PHI masking** | Detects and masks SSN, email, phone, names before sending to LLM |
| **SQL validation** | Structural validation using JSQLParser |
| **Confidence scoring** | Multi-factor score (HIGH / MEDIUM / LOW) |
| **Live execution** | Execute the generated SQL against the real DB and return results |
| **Cache admin dashboard** | View, search, evict, warm-up Redis cache entries |
| **Normalize checker** | Check which canonical key multiple queries share |
| **Graceful degradation** | Runs without OpenAI key (keyword fallback), without Redis (cache bypassed) |

---

## Tech Stack

### Backend
- **Java 17** + **Spring Boot 3.2**
- **Spring AI** (OpenAI GPT-4o chat + text-embedding-3-small)
- **Pinecone** vector database (gRPC client)
- **Redis** semantic cache (two-tier: NORM + VEC)
- **PostgreSQL** primary datasource + **H2** fallback
- **JSQLParser** SQL validation

### Frontend (`seek-ui/`)
- **Next.js 14** (App Router)
- **Tailwind CSS** + **shadcn/ui** components
- **TypeScript**

---

## Project Structure

```
seek/                          ← Spring Boot backend
├── src/main/java/com/nlp/rag/seek/
│   ├── config/                ← AIConfig, DataSourceConfig, RedisConfig
│   ├── controller/            ← NL2SQLController, CacheAdminController,
│   │                             ChunkAdminController, SQLExecutionController
│   ├── model/                 ← Request/Response models
│   └── service/               ← SQLGenerationService, VectorStoreService,
│                                 SemanticCacheService, SQLExecutionService, ...
├── src/main/resources/
│   └── application.properties
├── Dockerfile
├── docker-compose.yml
└── pom.xml

seek-ui/                       ← Next.js frontend
├── src/app/
│   ├── page.tsx               ← Main query page
│   ├── admin/cache/page.tsx   ← Cache dashboard
│   └── admin/normalize/page.tsx ← Normalize checker
├── src/components/
│   ├── Navbar.tsx
│   ├── SQLResult.tsx
│   └── QueryResultTable.tsx
├── src/lib/api.ts             ← All Spring Boot API calls
└── src/types/api.ts           ← TypeScript types
```

---

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.9+
- Node.js 18+
- Redis (optional — cache gracefully bypassed if not running)
- PostgreSQL (optional — falls back to H2 in-memory)

### 1. Set environment variables

```bash
export OPENAI_API_KEY="sk-your-key-here"
export PINECONE_API_KEY="your-pinecone-key"        # optional
export PINECONE_ENVIRONMENT="gcp-starter"          # optional
export PINECONE_INDEX_NAME="rag-nl2sql"            # optional
```

### 2. Run the backend

```bash
cd seek
./mvnw spring-boot:run
# API runs on http://localhost:9095
```

### 3. Run the frontend

```bash
cd seek-ui
npm install
npm run dev
# UI runs on http://localhost:3000
```

### 4. Open the UI

Visit **http://localhost:3000** and type a natural language question.

---

## API Endpoints

### NL2SQL
```
POST /api/v1/nl2sql
{
  "query": "list all active employees",
  "databaseName": "ecommerce",
  "topK": 5,
  "source": "UI",
  "showSampleResults": true,
  "sampleLimit": 50
}
```

### SQL Execution
```
POST /api/v1/execute
{ "sql": "SELECT * FROM employees;", "rowLimit": 50 }
```

### Cache Admin
```
GET    /api/v1/admin/cache/health
GET    /api/v1/admin/cache/stats
GET    /api/v1/admin/cache/entries
DELETE /api/v1/admin/cache/evict/all     (Header: X-Confirm-Flush: yes)
POST   /api/v1/admin/cache/warmup
POST   /api/v1/admin/cache/normalize
```

---

## Docker

```bash
docker-compose up
# Backend on :8080, H2 console on :8082
```

---

## Environment Variables Reference

| Variable | Required | Description |
|---|---|---|
| `OPENAI_API_KEY` | Yes (for LLM) | OpenAI API key — enables GPT-4o and embeddings |
| `PINECONE_API_KEY` | No | Pinecone vector DB key — falls back to in-memory |
| `PINECONE_ENVIRONMENT` | No | Pinecone environment (e.g. `gcp-starter`) |
| `PINECONE_INDEX_NAME` | No | Pinecone index name (default: `rag-nl2sql`) |
| `REDIS_HOST` | No | Redis host (default: `localhost`) |
| `REDIS_PORT` | No | Redis port (default: `6379`) |

---

## License

MIT

