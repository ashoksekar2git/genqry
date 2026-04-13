# Genqry — NL2SQL RAG Pipeline

> **Natural Language → SQL** powered by **RAG + OpenAI GPT-4o + Pinecone + Redis**

Genqry lets you type a plain-English question like _"list all active employees with salary > 50000"_ and instantly get a validated, executable SQL query — with confidence scoring, PII masking, semantic caching, and live result execution.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Technologies & Core Concepts](#technologies--core-concepts)
3. [NL2SQL Generation Process](#nl2sql-generation-process)
4. [Document RAG Explained](#document-rag-explained)
5. [Hallucination Prevention](#hallucination-prevention)
6. [Features](#features)
7. [Tech Stack](#tech-stack)
8. [Project Structure](#project-structure)
9. [Quick Start](#quick-start)
10. [API Endpoints](#api-endpoints)
11. [Environment Variables](#environment-variables-reference)

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

## Technologies & Core Concepts

### 1. **Vector Embeddings & Dense Retrieval**

Vector embeddings are numerical representations of text that capture semantic meaning in high-dimensional space. Genqry uses **OpenAI's text-embedding-3-small** model to convert:

- **Database schema** → Semantic vectors (stored in Pinecone or in-memory)
- **User query** → Query embedding (compared against schema vectors)

**Why it matters:**
- **Semantic similarity** not keyword matching: "Get employee records" finds the `employees` table even if the user never mentions the table name
- **Top-K retrieval**: Retrieves only the most relevant schema chunks (default K=5), reducing LLM context noise
- **Ranked by cosine similarity**: Most semantically relevant chunks ranked first

```
User Query: "employees earning more than 50k"
    ↓
Vector Embedding: [0.234, -0.891, 0.156, ..., 0.045]  (1536-dim)
    ↓
Cosine Similarity Search in Pinecone
    ↓
Top-5 schema chunks:
  1. employees table schema (similarity: 0.94)
  2. salary column definition (similarity: 0.89)
  3. employee_id foreign key (similarity: 0.82)
  4. active_status column (similarity: 0.76)
  5. department hierarchy (similarity: 0.71)
```

### 2. **Semantic Search (Vector-based Retrieval)**

Traditional SQL databases search by exact keywords. Semantic search understands **intent and context**.

**Genqry's Semantic Search Pipeline:**

```
┌─────────────────────────────────┐
│ 1. Index Phase (Offline)        │
│   • Parse DB schema              │
│   • Chunk into logical units     │
│   • Embed each chunk             │
│   • Store in Pinecone            │
│   • Maintain chunk metadata      │
└─────────────────────────────────┘
           ↓
┌─────────────────────────────────┐
│ 2. Query Phase (Online)          │
│   • Normalize user query         │
│   • Embed normalized query       │
│   • Cosine similarity search     │
│   • Return Top-K chunks          │
│   • Re-rank by relevance         │
└─────────────────────────────────┘
```

**Example - What semantic search catches that keywords miss:**

| User Query | Keyword Search Result | Semantic Search Result |
|---|---|---|
| "show me salary data" | NO MATCH (no keyword match) | ✅ `employees` table with salary column |
| "how much do staff earn?" | NO MATCH | ✅ `compensation`, `salary_history` tables |
| "active staff count" | Matches only `active` | ✅ Retrieves `active_status` flag + employee count logic |

### 3. **Semantic Cache (Two-Tier Strategy)**

Semantic caching prevents redundant LLM API calls and vector searches for similar queries using **NORM + VEC strategy**:

#### **NORM Layer (Normalization-based cache)**
- **Canonical query normalization**: Converts "show me employees" and "list employees" to same canonical form
- **SHA-256 hash**: `NORM_KEY = SHA256(canonical_query_lowercase)`
- **Instant hit**: Exact match lookups in Redis (microseconds)
- **Use case**: Users re-running same query or slight variations

```
Query 1: "List active employees"
   ↓ Normalize → "list active employees" 
   ↓ Hash → "a3f8e2b1..."
   ↓ Cache MISS → Generate SQL → Store result

Query 2: "list active employees" (exact repeat)
   ↓ Normalize → "list active employees"
   ↓ Hash → "a3f8e2b1..." 
   ↓ Cache HIT! → Return cached SQL + metadata instantly
```

#### **VEC Layer (Semantic vector cache)**
- **Vector similarity matching**: Embeddings of similar but not identical queries
- **Cosine similarity threshold**: Cache hit if similarity > 0.95 (configurable)
- **Semantic grouping**: Groups "active employees", "staff who are active", "currently employed staff" together
- **Use case**: Paraphrased or semantically equivalent queries

```
Cache entry: "SELECT * FROM employees WHERE active=true"
   ↓
New Query: "show me active staff members"
   ↓ Embed both
   ↓ Cosine similarity = 0.96 (> threshold 0.95)
   ↓ Cache HIT! → Return cached SQL
```

**Benefits of two-tier semantic cache:**

| Layer | Speed | Coverage | Use Case |
|---|---|---|---|
| **NORM** | µs (Redis lookup) | Exact & normalized queries | Repeated queries, UI refinements |
| **VEC** | ms (similarity search) | Semantically equivalent | Query variations, paraphrasing |

**Cache Statistics Example:**
```
Total Queries: 1000
NORM Cache Hits: 320 (32%) - saved 32 LLM calls
VEC Cache Hits: 180 (18%) - saved 180 LLM calls  
Cache Miss: 500 (50%) - required LLM generation
Effective Cache Hit Rate: 50% | LLM calls reduced by 50%
```

### 4. **PII & PHI Masking (Before LLM Transmission)**

Personally Identifiable Information (PII) and Protected Health Information (PHI) must never reach third-party LLMs (like OpenAI). Genqry masks sensitive data **before** sending queries:

**Patterns Detected & Masked:**
```
SSN: 123-45-6789           → [MASKED_SSN]
Email: john@company.com    → [MASKED_EMAIL]
Phone: +1-234-567-8900     → [MASKED_PHONE]
Credit Card: 4532-1234-... → [MASKED_CC]
Name: John Smith           → [MASKED_NAME] (optional context-aware)
Medical ID: MED-12345      → [MASKED_MEDICAL_ID]
```

**Process Flow:**
```
User Query (PII-containing):
"Find customer john@company.com with SSN 123-45-6789"
   ↓
PII Detection & Masking:
"Find customer [MASKED_EMAIL] with SSN [MASKED_SSN]"
   ↓
Send to OpenAI (safe - no PII exposed)
   ↓
Unmask in Final SQL:
"SELECT * FROM customers WHERE email='john@company.com' AND ssn='123-45-6789'"
```

**Masking Reversal Logic:**
- Maintains mapping: `[MASKED_EMAIL] → john@company.com`
- Substitutes original values back in final SQL
- Ensures LLM never sees actual PII, but SQL is still correct

### 5. **Abbreviated Database Name Column Enrichment**

Real databases often use abbreviations. Genqry enriches user queries with full column/table names:

**Example - Ecommerce Database:**
```
Raw Schema:
├── cust (instead of customers)
│   ├── cust_id
│   ├── cust_email
│   ├── cust_created_dt
├── ord (instead of orders)
│   ├── ord_id
│   ├── ord_total_amt

Enrichment Dictionary (from abbreviations.json):
{
  "cust": "customers",
  "ord": "orders",
  "cust_id": "customer_id",
  "ord_total_amt": "order_total_amount",
  "dt": "date"
}

User Query: "orders by customer"
   ↓
Enriched Query Context:
"orders (ord table) by customer (cust)"
   ↓
Vector Search finds: ord table + cust table
   ↓
LLM generates correct SQL using real column names
```

**How it works:**
1. **Load abbreviations.json** on startup (maps short names → full names)
2. **Include in prompt context**: "When you see 'cust', think 'customers' table"
3. **Column mapping in schema chunks**: Pinecone vectors encode both abbreviations and full names
4. **Semantic richness**: LLM understands both "cust_id" and "customer_id" mean the same thing

### 6. **Prompt Engineering for Accuracy**

Genqry uses multi-layered prompt engineering to reduce ambiguity:

```
SYSTEM PROMPT:
"You are a precise SQL query generator for [DATABASE].
You ONLY use schema elements provided.
For ambiguous columns, prefer the most specific (e.g., user_email over email).
Always use table aliases when joining.
Return ONLY valid SQL syntax."

USER PROMPT:
"
Database: ecommerce
Schema Summary:
- customers (cust): id, email, created_date, status
- orders (ord): id, customer_id, total, order_date, status
- order_items (oi): id, order_id, product_id, quantity, price

Question: [User's natural language query]

Generate the SQL query that answers this question.
Ensure:
1. Column names match schema exactly
2. Use proper JOINs where needed
3. Include WHERE filters for active records if not specified
4. Do NOT invent columns or tables
"
```

---

## NL2SQL Generation Process

### Step-by-Step Execution Flow

```
1. USER INPUT
   ├─ Query: "show me active employees with salary > 50000"
   ├─ Database: "ecommerce"
   └─ Request metadata (topK=5, source=UI)

2. PII DETECTION & MASKING
   ├─ Scan for SSN, email, phone, names
   ├─ Replace with [MASKED_*] tokens
   └─ Store mapping for later unmasking

3. SEMANTIC CACHE LOOKUP (Two-tier)
   ├─ NORM: Normalize query → SHA-256 hash → Redis lookup
   │   └─ HIT: Return cached SQL + metadata (0.1ms)
   │   └─ MISS: Continue to VEC layer
   ├─ VEC: Embed query → Cosine similarity search in Redis
   │   └─ HIT (similarity > 0.95): Return cached result (5ms)
   │   └─ MISS: Continue to generation

4. VECTOR SEARCH (Pinecone or In-memory)
   ├─ Embed query using text-embedding-3-small
   ├─ Retrieve Top-K (default 5) schema chunks
   ├─ Re-rank by relevance score
   └─ Include chunk metadata (table names, column definitions)

5. PROMPT ENGINEERING
   ├─ Build system prompt (role, constraints, format)
   ├─ Include retrieved schema chunks (RAG context)
   ├─ Append user query
   ├─ Add special instructions (no hallucination, strict schema)
   └─ Optionally inject business rules from BusinessRulesForPrompts.json

6. LLM GENERATION (OpenAI GPT-4o)
   ├─ Send engineered prompt to GPT-4o
   ├─ Receive generated SQL
   ├─ Extract confidence metadata
   └─ Store generation timestamp

7. SQL VALIDATION
   ├─ Parse SQL using JSQLParser
   ├─ Validate syntax correctness
   ├─ Verify tables exist in schema
   ├─ Verify columns referenced exist
   └─ Check for injection risks (basic)

8. CONFIDENCE SCORING (Multi-factor)
   ├─ Schema coverage: % of query terms matched in top-K
   ├─ Semantic similarity: cosine similarity of query embedding
   ├─ Validation success: JSQLParser passed validation
   ├─ Cache source: HIT=HIGH, MISS=MEDIUM/LOW
   └─ Final Score: HIGH (all pass) / MEDIUM (mostly) / LOW (risky)

9. CACHE STORAGE
   ├─ Store SQL + metadata in Redis
   ├─ Index by both NORM key and VEC embedding
   ├─ TTL: configurable (default 24 hours)
   └─ Metadata: query, sql, confidence, timestamp

10. RESPONSE
    ├─ Return SQL query
    ├─ Confidence level (HIGH/MEDIUM/LOW)
    ├─ Explanation of assumptions
    ├─ Retrieved schema chunks (for transparency)
    ├─ Generation time & cache source
    └─ Optional: Sample results from live execution

11. OPTIONAL: SQL EXECUTION
    ├─ If showSampleResults=true
    ├─ Execute against PostgreSQL / H2
    ├─ Return first N rows (default 50)
    ├─ Include row count & column types
    └─ Handle errors gracefully
```

---

## Document RAG Explained

**RAG = Retrieval Augmented Generation**

Traditional LLMs (like GPT-4o) have knowledge cutoff dates and cannot access real-time database schemas. RAG bridges this gap:

### How RAG Works in Genqry

```
┌─────────────────────────────────────────────────────────────────┐
│  Without RAG (Traditional LLM)                                  │
│                                                                 │
│  User: "List employees with salary > 50k"                      │
│    ↓                                                            │
│  LLM: Trained on generic data, doesn't know your schema        │
│    ↓                                                            │
│  Output: "SELECT * FROM staff WHERE salary > 50000"            │
│          (Might be wrong table name, wrong column name)        │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  With RAG (Genqry Pipeline)                                     │
│                                                                 │
│  Step 1: RETRIEVE                                               │
│  ├─ User: "List employees with salary > 50k"                   │
│  ├─ Embed query → [0.23, -0.89, ...]                           │
│  └─ Search schema vectors → Top-5 chunks:                      │
│     • employees table definition                               │
│     • salary column (type: DECIMAL, indexed)                   │
│     • employee_status (active/inactive)                        │
│     • compensation table schema                                │
│     • payroll policy rules                                     │
│                                                                 │
│  Step 2: AUGMENT                                                │
│  ├─ Build context window with retrieved chunks                 │
│  ├─ Add system instructions (strict schema only)               │
│  ├─ Include business rules (if applicable)                     │
│  └─ Append user query                                          │
│                                                                 │
│  Step 3: GENERATE                                               │
│  ├─ Send augmented prompt to GPT-4o                            │
│  └─ LLM generates SQL with full database context               │
│     SELECT * FROM employees                                    │
│     WHERE salary > 50000 AND active_status = 'true'            │
│                                                                 │
│  Result: 100% accurate SQL with correct table & column names   │
└─────────────────────────────────────────────────────────────────┘
```

### RAG Components in Genqry

| Component | Purpose | Technology |
|---|---|---|
| **Document Store** | Raw database schema, business rules, examples | JSON files (ecommerce_schema.json, BusinessRulesForPrompts.json) |
| **Chunker** | Break schema into logical units | Custom logic: tables, columns, relationships |
| **Embedder** | Convert text to vectors | OpenAI text-embedding-3-small (1536-dim) |
| **Vector DB** | Store & search embeddings | Pinecone (cloud) or in-memory (fallback) |
| **Retriever** | Fetch Top-K relevant chunks | Cosine similarity search |
| **Augmenter** | Build augmented prompt context | Spring AI LLM templates |
| **Generator** | Create SQL from augmented context | OpenAI GPT-4o (chat model) |

---

## Hallucination Prevention

LLMs are prone to "hallucinations" — generating confident but completely false outputs. Genqry uses **multi-layered guardrails**:

### 1. **Schema Validation Layer**
```
LLM generates:  "SELECT * FROM staff_records WHERE emp_id > 100"

Validation checks:
├─ Table "staff_records" exists? ❌ NOT FOUND
├─ Did you mean: "employees"? ✅ SIMILAR (Levenshtein distance)
├─ Confidence downgrade: HIGH → MEDIUM

Generate alert: "Table 'staff_records' not found. Did you mean 'employees'?"
```

### 2. **Semantic Retrieval as Truth Source**
```
Constraint: "Only use schema elements from Top-K retrieval results"

Benefit:
├─ LLM cannot invent tables/columns (not in context)
├─ If schema chunk mentions "employees" table
├─ LLM must use "employees" or face validation failure
└─ Dramatically reduces hallucination

Example:
Retrieved chunks explicitly mention:
- Table: employees (columns: id, name, salary, active_status)
- Table: departments (columns: id, dept_name)

LLM generates:
"SELECT e.name FROM employees e JOIN depts d ON e.dept_id = d.id"

Validation:
✗ Column "dept_id" NOT in retrieved chunks
✗ Confidence downgrade: HIGH → LOW
✗ Alert: "Column 'dept_id' not referenced in schema"
```

### 3. **JSQLParser Strict Validation**
```
LLM output: "SELECT * FROM employees;"

JSQLParser checks:
├─ Syntax: Valid SQL ✅
├─ Schema: All tables/columns resolvable ✅
├─ Aliases: Properly scoped ✅
├─ Joins: Correct ON conditions ✅
└─ Result: PASS → Confidence HIGH

LLM output: "SELECT * FROM employes WHERE salary > 50000"

JSQLParser checks:
├─ Syntax: Valid SQL ✅
├─ Schema: "employes" table NOT FOUND ❌
└─ Result: FAIL → Confidence LOW
```

### 4. **Confidence Scoring Prevents False Positives**
```
Confidence = (schema_coverage + semantic_similarity + validation_pass + cache_hit) / 4

HIGH:  Score ≥ 0.85 (Semantic + Validation all pass, schema covered)
MEDIUM: 0.65-0.84 (Some validation warnings)
LOW:   < 0.65 (Significant issues or cache miss)

User sees:
├─ SQL: "SELECT * FROM employees WHERE salary > 50000"
├─ Confidence: MEDIUM ⚠️
├─ Reason: "Table 'employees' found, but column 'salary' not in top-K chunks. 
│           Consider: 'compensation' or 'pay_amount' instead"
└─ Action: User decides before executing
```

### 5. **Abbreviated Name Disambiguation**
```
Schema has both:
├─ sal (abbreviation for salary)
└─ salary (full name)

User query: "employees with sal > 50000"

Without enrichment:
❌ LLM might generate: "SELECT * FROM employees WHERE salary > 50000"
❌ (Doesn't exist — actual column is "sal")

With enrichment:
├─ Load abbreviations.json
├─ Enhance prompt: "When user says 'sal', they mean column 'sal' or 'salary'"
├─ LLM generates: "SELECT * FROM employees WHERE sal > 50000"
└─ ✅ CORRECT

Schema mapping ensures disambiguation:
• Abbreviation resolution happens BEFORE LLM
• Ambiguity surfaced as part of schema context
• LLM sees both forms, reduces guessing
```

### 6. **Business Rules Injection**
```
BusinessRulesForPrompts.json:
{
  "employee_status_filter": "Always filter WHERE employee_status IN ('ACTIVE', 'ON_LEAVE')",
  "salary_range_constraint": "Salary must be between 10000 and 500000",
  "date_format": "Always use YYYY-MM-DD format"
}

System prompt includes:
"When querying employees, ALWAYS apply: 
 WHERE employee_status IN ('ACTIVE', 'ON_LEAVE')"

User asks: "Show all employees"

LLM generates:
SELECT * FROM employees 
WHERE employee_status IN ('ACTIVE', 'ON_LEAVE')

Instead of: SELECT * FROM employees (might include inactive)
```

### 7. **Testing Against Known Queries**
```
Known query examples (in vector index):
├─ NL: "active employees by department"
│  SQL: "SELECT d.name, COUNT(*) FROM employees e 
│        JOIN departments d ON e.dept_id = d.id 
│        WHERE e.active_status = true GROUP BY d.name"
│
├─ NL: "top 10 highest paid employees"
│  SQL: "SELECT name, salary FROM employees 
│        ORDER BY salary DESC LIMIT 10"

New query: "list highest paid active staff"

Semantic search finds: "top 10 highest paid employees" (similar)
├─ Retrieves reference SQL structure
├─ LLM adapts it (replaces "employees" → confirmed ✅)
├─ Adds active filter (from business rules)
└─ Result: High confidence due to similar known query
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

### Backend Architecture

#### **Core Framework**
- **Java 17** + **Spring Boot 3.2**
  - Modern async/reactive capabilities
  - Spring AI integration for LLM orchestration
  - Robust error handling & observability
  - Production-grade configuration management

#### **LLM & Embeddings**
- **OpenAI GPT-4o** (via Spring AI)
  - State-of-the-art reasoning for SQL generation
  - Multimodal understanding (future extensibility)
  - Function calling for structured outputs
  - Temperature-controlled determinism

- **OpenAI text-embedding-3-small**
  - 1536-dimensional embeddings (optimal size/quality tradeoff)
  - ~100 tokens per embedding (~$0.02 per 1M)
  - Normalized output (cosine similarity ready)
  - Used for: schema indexing, query embedding, similarity matching

#### **Vector Database**
- **Pinecone** (Primary - Cloud)
  - Serverless vector search at scale
  - gRPC-based client (faster than REST)
  - Metadata filtering on vectors
  - Pod-based isolation & autoscaling
  - Fallback: In-memory vector search when Pinecone unavailable

#### **Semantic Cache & Session Store**
- **Redis**
  - **NORM layer**: SHA-256 hashed canonical queries (string keys)
  - **VEC layer**: Vector embeddings stored as sorted sets (for cosine similarity)
  - **TTL management**: Automatic expiration of stale cache entries
  - **Pub/Sub**: Cache invalidation across instances
  - **Graceful degradation**: System works without Redis (cache skipped)

#### **SQL Processing & Validation**
- **JSQLParser**
  - Parses SQL without executing (safe)
  - Validates table/column references
  - Detects JOIN complexity
  - Extracts selected columns for confidence scoring
  - Checks for common injection patterns

#### **Primary Datasource**
- **PostgreSQL** (Production)
  - ACID compliance for query execution results
  - Support for complex joins & aggregations
  - Indexes for fast retrieval

- **H2** (Development/Fallback)
  - In-memory database for testing
  - No external dependencies
  - Fast iteration in dev environment

#### **Additional Libraries**
- **Spring Data JPA**: ORM for database operations
- **Spring Security**: API authentication & authorization
- **Lombok**: Reduce boilerplate (getters, setters, builders)
- **Jackson**: JSON serialization/deserialization
- **Micrometer**: Application metrics & observability
- **Log4j 2**: Structured logging

### Frontend Stack

#### **Framework & Language**
- **Next.js 14** (App Router)
  - Server-side rendering (SEO, performance)
  - App Router for nested layouts
  - API routes (proxy to backend)
  - Incremental static regeneration

- **TypeScript**
  - Type safety across UI layer
  - Better IDE support & refactoring
  - Catch errors at compile time

#### **Styling & Components**
- **Tailwind CSS v3**
  - Utility-first CSS (rapid UI development)
  - Dark mode support
  - Responsive design utilities

- **shadcn/ui**
  - Unstyled, accessible components
  - Radix UI primitives underneath
  - Copy-paste component customization

#### **State Management & HTTP**
- **React Hooks** (useState, useContext)
  - Lightweight state management
  - No external Redux/Zustand needed (for current scope)

- **Axios/Fetch API**
  - HTTP client for backend communication
  - Request/response interceptors
  - Error handling & retry logic

---

## Project Structure

### Backend Structure

```
genqry/                                    ← Spring Boot Backend Root
├── src/main/java/com/nlp/rag/
│   ├── config/
│   │   ├── AIConfig.java                 ← OpenAI & LLM client setup
│   │   ├── PineconeConfig.java           ← Vector DB initialization
│   │   ├── RedisConfig.java              ← Semantic cache setup (NORM + VEC layers)
│   │   ├── DataSourceConfig.java         ← PostgreSQL/H2 datasource config
│   │   └── SecurityConfig.java           ← Spring Security (optional)
│   │
│   ├── controller/
│   │   ├── NL2SQLController.java         ← Main API: POST /api/v1/nl2sql
│   │   ├── SQLExecutionController.java   ← POST /api/v1/execute (run SQL)
│   │   ├── CacheAdminController.java     ← GET/DELETE /api/v1/admin/cache/*
│   │   ├── ChunkAdminController.java     ← Manage vector index chunks
│   │   └── SchemaController.java         ← GET /api/v1/schema/* (read-only)
│   │
│   ├── model/
│   │   ├── request/
│   │   │   ├── NL2SQLRequest.java        ← { query, databaseName, topK, showSampleResults }
│   │   │   └── ExecuteRequest.java       ← { sql, rowLimit }
│   │   └── response/
│   │       ├── NL2SQLResponse.java       ← { sql, confidence, explanation, chunks, ... }
│   │       ├── SQLExecutionResponse.java ← { rows, rowCount, columns, executionTime }
│   │       └── CacheStats.java           ← { hitRate, entriesCount, memoryUsage }
│   │
│   ├── service/
│   │   ├── SQLGenerationService.java     ← Orchestrates NL→SQL pipeline
│   │   ├── VectorStoreService.java       ← Manages Pinecone/in-memory indexing
│   │   ├── SemanticCacheService.java     ← NORM + VEC cache layers
│   │   ├── PiiMaskingService.java        ← Detects & masks SSN, email, phone, etc.
│   │   ├── PromptEngineeringService.java ← Builds LLM prompts with RAG context
│   │   ├── SQLValidationService.java     ← JSQLParser validation & error handling
│   │   ├── ConfidenceScoringService.java ← Multi-factor confidence calculation
│   │   ├── SQLExecutionService.java      ← Executes SQL against DB (with safety checks)
│   │   ├── SchemaEnrichmentService.java  ← Abbreviation mapping & semantic enrichment
│   │   └── BusinessRulesService.java     ← Loads/applies business rules from JSON
│   │
│   ├── util/
│   │   ├── EmbeddingUtil.java            ← Vector utilities (cosine similarity, etc.)
│   │   ├── QueryNormalizer.java          ← Canonicalizes queries for NORM cache
│   │   └── SchemaChunker.java            ← Splits schema into indexable chunks
│   │
│   └── exception/
│       ├── NL2SQLException.java          ← Base exception
│       ├── ValidationException.java      ← SQL validation failures
│       └── PiiDetectedException.java     ← PII detected (won't send to LLM)
│
├── src/main/resources/
│   ├── application.properties             ← Default config
│   ├── application-dev.properties         ← Development overrides
│   ├── application-prod.properties        ← Production overrides
│   ├── application-cloud.properties       ← Cloud deployments (Pinecone)
│   ├── school.sql                         ← Sample schema for testing
│   │
│   ├── db/
│   │   └── migration/
│   │       ├── V0__create_webauthn_credentials_table.sql
│   │       ├── V1__genqry_schema.sql     ← Main Genqry tables setup
│   │       ├── V2__add_password_reset_tokens.sql
│   │       └── ... (Flyway migrations)
│   │
│   ├── metadata/
│   │   └── employees_metadata.json       ← Sample table descriptions
│   │
│   ├── sql/
│   │   └── ecommerce_schema.sql          ← Full ecommerce schema definition
│   │
│   └── supportingFiles/
│       ├── abbreviations.json            ← { "cust": "customers", ... }
│       ├── BusinessRulesForPrompts.json  ← Domain-specific business rules
│       ├── root_ecommerce_DBSchema.json  ← Complete schema metadata
│       ├── semantic.json                 ← Example semantic mappings
│       └── vector_index_ecommerce.json   ← Precomputed embeddings (optional cache)
│
├── pom.xml                               ← Maven dependencies
├── Dockerfile                            ← Docker image build
└── docker-compose.yml                    ← Local dev environment (PostgreSQL, Redis, Backend)
```

### Frontend Structure

```
genqry-ui/                                 ← Next.js Frontend Root
├── src/
│   ├── app/
│   │   ├── page.tsx                      ← Main query interface (/)
│   │   ├── layout.tsx                    ← Root layout wrapper
│   │   ├── query/
│   │   │   └── [id]/page.tsx             ← View saved query by ID
│   │   └── admin/
│   │       ├── cache/
│   │       │   └── page.tsx              ← Cache stats & management dashboard
│   │       ├── normalize/
│   │       │   └── page.tsx              ← NORM cache key inspector
│   │       └── schema/
│   │           └── page.tsx              ← Schema browser & search
│   │
│   ├── components/
│   │   ├── Navbar.tsx                    ← Navigation & theme toggle
│   │   ├── QueryForm.tsx                 ← Input form for NL queries
│   │   ├── SQLEditor.tsx                 ← Syntax-highlighted SQL display
│   │   ├── SQLResult.tsx                 ← Result tabs (SQL / Explanation / Chunks)
│   │   ├── QueryResultTable.tsx          ← Paginated result grid
│   │   ├── CacheViewer.tsx               ← Cache entry browser
│   │   └── SchemaViewer.tsx              ← Interactive schema explorer
│   │
│   ├── lib/
│   │   ├── api.ts                        ← All API calls to backend
│   │   │                                    (nl2sql, execute, cacheAdmin, etc.)
│   │   └── utils.ts                      ← Utility functions (formatting, etc.)
│   │
│   ├── types/
│   │   └── api.ts                        ← TypeScript interfaces matching backend
│   │                                       (NL2SQLRequest, NL2SQLResponse, etc.)
│   │
│   ├── styles/
│   │   └── globals.css                   ← Tailwind imports & global styles
│   │
│   ├── package.json                      ← npm dependencies
│   ├── tsconfig.json                     ← TypeScript config
│   ├── tailwind.config.js                ← Tailwind customization
│   └── next.config.js                    ← Next.js customization
```

---

## Quick Start

### Prerequisites
- **Java 17+** (check with `java -version`)
- **Maven 3.9+** (check with `mvn -version`)
- **Node.js 18+** (check with `node -version`)
- **Redis 6.0+** (optional — gracefully bypassed if not running)
- **PostgreSQL 12+** (optional — falls back to H2 in-memory)

### Option 1: Docker Compose (Recommended for Local Development)

The simplest way to run Genqry with all dependencies:

```bash
# Clone/navigate to repo
cd /Users/ashoksekar/workspace/genAi/genqry

# Set environment variables
export OPENAI_API_KEY="sk-your-openai-key-here"
export PINECONE_API_KEY="your-pinecone-key"        # optional
export PINECONE_ENVIRONMENT="gcp-starter"          # optional

# Start all services (PostgreSQL, Redis, Backend)
docker-compose up

# In another terminal, start the frontend
cd genqry-ui
npm install
npm run dev

# Open http://localhost:3000 in your browser
```

**What runs where:**
- Backend: http://localhost:8080 (Spring Boot with H2 console at :8082)
- Frontend: http://localhost:3000 (Next.js)
- Redis: localhost:6379
- PostgreSQL: localhost:5432

### Option 2: Local Development (Manual)

For iterative development without Docker:

#### Step 1: Set environment variables

```bash
# macOS/Linux
export OPENAI_API_KEY="sk-your-openai-key-here"
export PINECONE_API_KEY="your-pinecone-key"
export PINECONE_ENVIRONMENT="gcp-starter"
export PINECONE_INDEX_NAME="rag-nl2sql"

# Optional: point to local Redis
export REDIS_HOST="localhost"
export REDIS_PORT="6379"
```

#### Step 2: Start Redis locally (optional but recommended)

```bash
# Using Homebrew
brew install redis
brew services start redis

# Or using Docker
docker run -d -p 6379:6379 redis:7-alpine
```

#### Step 3: Start PostgreSQL (optional — falls back to H2 if not available)

```bash
# Using Homebrew
brew install postgresql@15
brew services start postgresql@15

# Or using Docker
docker run -d \
  --name postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=ecommerce \
  -p 5432:5432 \
  postgres:15-alpine
```

#### Step 4: Run the backend

```bash
cd /Users/ashoksekar/workspace/genAi/genqry

# Build and run with Maven
./mvnw clean install -DskipTests
./mvnw spring-boot:run

# OR run the built JAR directly
java -jar target/genqry-0.0.1-SNAPSHOT.jar

# Backend is ready when you see:
# "Tomcat started on port(s): 9095 (http) with context path ''"
```

**Backend runs on:** http://localhost:9095

#### Step 5: Run the frontend

```bash
cd genqry-ui

# Install dependencies
npm install

# Start dev server
npm run dev

# Frontend is ready when you see:
# "Local: http://localhost:3000"
```

**Frontend runs on:** http://localhost:3000

#### Step 6: Open the UI

Visit **http://localhost:3000** in your browser and type a natural language query!

### Option 3: Production Deployment

For production environments:

```bash
# Build optimized JAR
./mvnw clean package -DskipTests -P prod

# Build Docker image
docker build -t genqry:latest .

# Push to registry (e.g., Docker Hub, ECR)
docker tag genqry:latest your-registry.com/genqry:latest
docker push your-registry.com/genqry:latest

# Deploy with environment variables
docker run -d \
  --name genqry \
  -e OPENAI_API_KEY="sk-..." \
  -e PINECONE_API_KEY="pk-..." \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://prod-db:5432/genqry" \
  -e SPRING_REDIS_HOST="prod-redis" \
  -e SPRING_REDIS_PASSWORD="your-secure-password" \
  -p 8080:9095 \
  your-registry.com/genqry:latest
```

### Troubleshooting

#### Backend won't start: "OPENAI_API_KEY not found"
```bash
# Ensure env var is set and exported
export OPENAI_API_KEY="sk-your-key"

# Verify it's set
echo $OPENAI_API_KEY

# Run with explicit property
./mvnw spring-boot:run -Dspring-boot.run.arguments="--openai.api-key=$OPENAI_API_KEY"
```

#### Redis connection refused
```bash
# Check if Redis is running
redis-cli ping  # Should return "PONG"

# If not running, start it
brew services start redis

# Or run in Docker
docker run -d -p 6379:6379 redis:7-alpine
```

#### Port already in use
```bash
# Change port for backend (application.properties)
export SERVER_PORT=9096

# Change port for frontend (package.json)
npm run dev -- -p 3001
```

#### H2 console access (when using H2 instead of PostgreSQL)
```
URL: http://localhost:9095/h2-console
JDBC URL: jdbc:h2:mem:testdb
User: sa
Password: (empty)
```

---

## API Endpoints

## API Endpoints

### NL2SQL Query Generation

**Endpoint:** `POST /api/v1/nl2sql`

Converts natural language to SQL query with semantic caching and confidence scoring.

```bash
curl -X POST http://localhost:9095/api/v1/nl2sql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "list all active employees with salary greater than 50000",
    "databaseName": "ecommerce",
    "topK": 5,
    "source": "UI",
    "showSampleResults": true,
    "sampleLimit": 50
  }'
```

**Request Parameters:**
- `query` (string, required): Natural language question
- `databaseName` (string, required): Database name (e.g., "ecommerce", "school")
- `topK` (integer, optional, default: 5): Number of schema chunks to retrieve
- `source` (string, optional, default: "API"): Origin of request (for analytics)
- `showSampleResults` (boolean, optional, default: false): Execute and return sample rows
- `sampleLimit` (integer, optional, default: 50): Max rows to return in sample results

**Response:**
```json
{
  "sql": "SELECT * FROM employees WHERE active_status = true AND salary > 50000",
  "confidence": "HIGH",
  "explanation": "Query matches employees table with active_status and salary filters",
  "retrievedChunks": [
    {
      "id": "chunk-1",
      "content": "employees table: id, name, salary, active_status...",
      "similarity": 0.94
    }
  ],
  "generationTime": 245,
  "cacheSource": "MISS",
  "sampleResults": {
    "rows": [
      {"id": 1, "name": "John Doe", "salary": 75000, "active_status": true},
      {"id": 2, "name": "Jane Smith", "salary": 65000, "active_status": true}
    ],
    "rowCount": 2,
    "columns": ["id", "name", "salary", "active_status"]
  }
}
```

### SQL Execution

**Endpoint:** `POST /api/v1/execute`

Execute a SQL query against the database and return results.

```bash
curl -X POST http://localhost:9095/api/v1/execute \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT * FROM employees WHERE salary > 50000",
    "rowLimit": 100
  }'
```

**Request Parameters:**
- `sql` (string, required): SQL query to execute
- `rowLimit` (integer, optional, default: 50): Maximum rows to return

**Response:**
```json
{
  "rows": [
    {"id": 1, "name": "John Doe", "salary": 75000},
    {"id": 2, "name": "Jane Smith", "salary": 65000}
  ],
  "rowCount": 2,
  "totalCount": 2,
  "columns": ["id", "name", "salary"],
  "executionTime": 15,
  "error": null
}
```

### Cache Management

#### Get Cache Health Status
```
GET /api/v1/admin/cache/health
```

Returns operational status of the cache layer.

#### Get Cache Statistics
```
GET /api/v1/admin/cache/stats
```

Returns cache hit rate, entry count, memory usage.

#### List Cache Entries
```
GET /api/v1/admin/cache/entries?page=0&size=20
```

Browse all cached queries with metadata.

#### Clear All Cache
```
DELETE /api/v1/admin/cache/evict/all
Header: X-Confirm-Flush: yes
```

⚠️ WARNING: Clears all cached entries irreversibly.

#### Warm-up Cache
```
POST /api/v1/admin/cache/warmup
```

Pre-load cache with common queries.

#### Inspect Normalization Keys
```
POST /api/v1/admin/cache/normalize
{
  "query": "list active employees"
}
```

Returns the canonical form and SHA-256 NORM key for a query.

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

