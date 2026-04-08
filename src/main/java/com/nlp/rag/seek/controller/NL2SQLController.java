package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.model.NaturalLanguageQueryRequest;
import com.nlp.rag.seek.model.PiiSanitizationResult;
import com.nlp.rag.seek.model.PiiToken;
import com.nlp.rag.seek.model.SQLExecutionResult;
import com.nlp.rag.seek.model.SQLGenerationResponse;
import com.nlp.rag.seek.service.ClientSourceDetector;
import com.nlp.rag.seek.service.ClientSourceDetector.Source;
import com.nlp.rag.seek.service.PiiSanitizationService;
import com.nlp.rag.seek.service.SQLExecutionService;
import com.nlp.rag.seek.service.SQLFormatterService;
import com.nlp.rag.seek.service.SQLGenerationService;
import com.nlp.rag.seek.service.UserDbService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST Controller for Natural Language to SQL conversion and PII inspection.
 *
 * Automatically detects whether the caller is a browser UI or a
 * programmatic API client, and formats the SQL response accordingly:
 *
 *   UI  → formattedSQL has newlines + indentation (human-readable)
 *   API → formattedSQL is compact single-line (machine-consumable)
 *
 * Detection order:
 *   1. request.source field in JSON body  ("UI" | "API" | "CURL" | "BATCH")
 *   2. X-Client-Source header
 *   3. Accept: text/html
 *   4. Origin / Referer headers present
 *   5. X-Requested-With: XMLHttpRequest
 *   6. User-Agent (Mozilla/Chrome/Safari → UI, curl/Java → API)
 *   7. Default → API
 *
 * Cache administration: {@link CacheAdminController} (/api/v1/admin/cache/*)
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "NL2SQL", description = "Natural Language to SQL conversion pipeline")
public class NL2SQLController {

    private static final Logger logger = LoggerFactory.getLogger(NL2SQLController.class);

    @Autowired private SQLGenerationService   sqlGenerationService;
    @Autowired private PiiSanitizationService piiSanitizationService;
    @Autowired private ClientSourceDetector   clientSourceDetector;
    @Autowired private SQLFormatterService    sqlFormatterService;
    @Autowired private SQLExecutionService    sqlExecutionService;
    @Autowired(required = false)
    private UserDbService userDbService;

    // ── NL2SQL ────────────────────────────2026-03-25T08:12:23.914-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:12:23.926-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg in decimal
    //2026-03-25T08:12:23.927-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T08:12:23.927-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T08:12:23.927-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T08:12:23.927-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg in decimal' | db='ecommerce' | topK=3
    //2026-03-25T08:12:23.928-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T08:12:23.929-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:12:23.936-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:12:23.936-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SemanticCacheService  : Tier-1 MISS key='genqry:cache:ecommerce:norm:ef3d36489796e229'  canonical='get 1 decimal heavier kg product than'
    //2026-03-25T08:12:25.170-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SemanticCacheService  : Tier-2 MISS — no vector entries for db='ecommerce'
    //2026-03-25T08:12:25.954-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.VectorStoreService  : Semantic search 'List all the products which is heavier than 1 kg in decimal' → top 3 results:
    //2026-03-25T08:12:25.955-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.3142593869779333 | table=orderdetails#0 col=orderdetails
    //2026-03-25T08:12:25.955-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2958051463854882 | table=products#0 col=products
    //2026-03-25T08:12:25.955-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2847524457704409 | table=productsuppliers.productid#0 col=productsuppliers
    //2026-03-25T08:12:25.956-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : FK-expansion: injected table 'orders' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:12:25.956-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : FK-expansion: injected table 'categories' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:12:25.956-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : FK-expansion: injected table 'suppliers' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:12:25.958-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : EAV-injection: force-injected EAV table 'attributes' for query terms [weight]
    //2026-03-25T08:12:25.958-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : EAV-injection: force-injected EAV table 'productattributevalues' for query terms [weight]
    //2026-03-25T08:12:25.958-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : TopK cap applied: 8 chunks → 3 (topK=3)
    //2026-03-25T08:12:25.959-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping attributes#0 [score=0.500] for table 'attributes'
    //2026-03-25T08:12:25.959-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping productattributevalues#0 [score=0.500] for table 'productattributevalues'
    //2026-03-25T08:12:25.959-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping orderdetails#0 [score=0.314] for table 'orderdetails'
    //2026-03-25T08:12:25.959-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : Sending sanitized query to LLM with top-3 schema chunks
    //2026-03-25T08:12:25.964-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : ══════════════ PROMPT SENT TO LLM ══════════════
    //You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query..
    //
    //Rules:
    //1. Use ONLY the tables and columns that appear in the schema context below.
    //2. Write standard SQL compatible with PostgreSQL.
    //3. Use explicit JOINs with ON clauses — never implicit comma joins.
    //4. Add a WHERE clause whenever a filter is implied.
    //5. Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) when appropriate.
    //6. Add ORDER BY when a ranking or 'top N' is implied.
    //7. Return ONLY the SQL statement — no prose, no markdown fences.
    //8. CRITICAL JOIN RULE — if two tables doesnt have foreign key relationship then dont join them
    //9. If the user's question refers to a concept, entity, or table that does NOT exist anywhere in the provided schema context, do NOT guess or map it to the closest table. Instead respond with exactly: DATA_NOT_AVAILABLE: The database schema does not contain a table or concept matching '<user term>'. Available tables are: <list the table names from the schema context>.
    //10. MPORTANT: ALL tables and columns listed in the schema context ARE valid and exist in the database — you MUST use them if they match the user's question
    //11. End the sql statement with a semicolon.
    //
    //Follow the below rules for EAV tables:
    //1. NEVER use attribute names (e.g. Weight, Color, Size) as SQL column names.
    //2. Use WHERE <attr_name_col> = '<key>' to select a specific attribute.
    //3. Use CAST(<attr_value_col> AS <type>) when reading numeric or date values.
    //4. For multiple attributes in one row, use conditional aggregation: MAX(CASE WHEN <attr_name_col> = '<key1>' THEN CAST(<attr_value_col> AS <type>) END) AS <key1>
    //5. Join to the entity table via the entity_id column shown in the context.
    //6. EAV attributes retrieved for this query are listed in the schema context — use their FILTER and CAST HINT values exactly.
    //7. Attribute names are stored as ROW VALUES in the attribute name column.
    //8. Join the entity table to the EAV data table on the entity ID column.
    //9. Join the EAV data table to the attributes table on the attribute ID column.
    //
    //Database: ecommerce
    //
    //IMPORTANT: ALL tables listed below are valid and exist in the database. You MUST use any table or column from the schema context if it is relevant to the question. Only respond with DATA_NOT_AVAILABLE if the user's question refers to a concept that has NO matching table or column ANYWHERE in the schema context below.
    //
    //--- RETRIEVED SCHEMA CHUNKS ---
    //• attributes#0
    //  TABLE         : attributes
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : name
    //  ATTR VALUE COL: datatype
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.
    //  TEXT          : Table: attributes. Description: EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE name = '<attribute_key>' to filter, and read datatype for the value. Entity linked via attributeid. Columns: attributeid (serial) [PK]; name (character varying); datatype (character varying); unit (character varying); description (text); createdat (timestamp); Column descriptions: attributeid:
    //
    //• productattributevalues#0
    //  TABLE         : productattributevalues
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : attribute_name
    //  ATTR VALUE COL: value
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE attribute_name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.
    //  TEXT          : Table: productattributevalues. Description: EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE attribute_name = '<attribute_key>' to filter, and read value for the value. Entity linked via attributeid
    //
    //• orderdetails#0
    //  TABLE   : orderdetails
    //  COLUMNS : orderdetailid:serial, orderid:integer, productid:integer, quantity:integer, unitprice:numeric, linetotal:numeric
    //  PKs     : orderdetailid
    //  FKs     : orderid->orders.orderid, productid->products.productid
    //  CONTEXT : Line items for each order. Each row represents one product in an order.
    //  DESC    : Line items for each order. Each row represents one product in an order.
    //  TEXT    : Table: orderdetails. Description: Line items for each order. Each row represents one product in an order.. Columns: orderdetailid (serial) [PK]; orderid (integer) [FK -> orders.orderid]; productid (integer) [FK -> products.productid]; quantity (integer); unitprice (numeric); linetotal (numeric); Column descriptions: orderdetailid: orderdetailid (serial). orderid: orderid (integer). productid: productid (integer). quantity: quantity (integer). unitprice: unitprice (numeric). linetotal: linetotal (numeric).
    //--- END OF SCHEMA CHUNKS ---
    //
    //Natural-language question: List all the products which is heavier than 1 kg in decimal
    //
    //SQL:
    //
    //══════════════ END OF PROMPT ══════════════
    //2026-03-25T08:12:27.844-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : ══════════════ LLM RESPONSE ══════════════
    //DATA_NOT_AVAILABLE: The database schema does not contain a table or concept matching 'products'. Available tables are: attributes, productattributevalues, orderdetails.
    //══════════════ END OF LLM RESPONSE ══════════════
    //2026-03-25T08:12:27.845-05:00  WARN 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLGenerationService  : LLM signalled DATA_NOT_AVAILABLE for query='List all the products which is heavier than 1 kg in decimal': The database schema does not contain a table or concept matching 'products'. Available tables are: attributes, productattributevalues, orderdetails.
    //2026-03-25T08:12:27.847-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.TranscriptService   : Error transcript appended → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts_03_25_26.json
    //2026-03-25T08:12:27.863-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.repository.UserRepository   : Transcript inserted | id=1086 | userId=1000 | status=FAILURE | dataSource=ecommerce
    //2026-03-25T08:12:27.873-05:00 DEBUG 48497 --- [genQry] [nio-9095-exec-8] com.nlp.rag.seek.service.UserDbService   : transcript_json JDBC type=org.postgresql.util.PGobject value-preview='{"remarks": "The requested data is not available in the database schema. The database schema does not contain a table or'
    //2026-03-25T08:12:27.878-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] com.nlp.rag.seek.service.UserDbService   : Exported 56 transcripts → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json for user='AshokSekar'
    //2026-03-25T08:12:27.879-05:00  INFO 48497 --- [genQry] [nio-9095-exec-8] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=false | executed=false
    //^C%                                                                                                                                                                                                              ashoksekar@ashoks-MacBook-Pro seek % ./mvnw clean package -DskipTests -q && java -jar target/genQry-0.0.1-SNAPSHOT.jar
    //
    //  .   ____          _            __ _ _
    // /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
    //( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
    // \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
    //  '  |____| .__|_| |_|_| |_\__, | / / / /
    // =========|_|==============|___/=/_/_/_/
    // :: Spring Boot ::                (v3.2.5)
    //
    //2026-03-25T08:35:11.926-05:00  INFO 53137 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Starting SeekApplication v0.0.1-SNAPSHOT using Java 17.0.18 with PID 53137 (/Users/ashoksekar/workspace/genAi/seek/target/genQry-0.0.1-SNAPSHOT.jar started by ashoksekar in /Users/ashoksekar/workspace/genAi/seek)
    //2026-03-25T08:35:11.928-05:00 DEBUG 53137 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Running with Spring Boot v3.2.5, Spring v6.1.6
    //2026-03-25T08:35:11.929-05:00  INFO 53137 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : No active profile set, falling back to 1 default profile: "default"
    //2026-03-25T08:35:13.103-05:00  INFO 53137 --- [genQry] [           main] c.nlp.rag.seek.config.JwtTokenProvider   : JwtTokenProvider initialised — expiration=86400000ms
    //2026-03-25T08:35:13.126-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.config.JwtAuthenticationFilter   : Filter 'jwtAuthenticationFilter' configured for use
    //2026-03-25T08:35:13.225-05:00  INFO 53137 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → PRIMARY (genQry) set to read-only mode
    //2026-03-25T08:35:13.227-05:00  INFO 53137 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → PRIMARY (genQry) reachable
    //2026-03-25T08:35:13.577-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.service.SQLExplanationService    : Explanation rules loaded from BusinessRulesForPrompts.json — 321 chars
    //2026-03-25T08:35:13.590-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    : Abbreviations loaded from supportingFiles/abbreviations.json — 152 entries across all sections
    //2026-03-25T08:35:13.591-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'employee' → 8 synonym(s)
    //2026-03-25T08:35:13.591-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'customer' → 5 synonym(s)
    //2026-03-25T08:35:13.591-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'order' → 5 synonym(s)
    //2026-03-25T08:35:13.591-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'product' → 5 synonym(s)
    //2026-03-25T08:35:13.591-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'department' → 5 synonym(s)
    //2026-03-25T08:35:13.591-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'invoice' → 4 synonym(s)
    //2026-03-25T08:35:13.591-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'payment' → 5 synonym(s)
    //2026-03-25T08:35:13.591-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'salary' → 5 synonym(s)
    //2026-03-25T08:35:13.591-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'address' → 4 synonym(s)
    //2026-03-25T08:35:13.591-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'account' → 4 synonym(s)
    //2026-03-25T08:35:13.591-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'vendor' → 4 synonym(s)
    //2026-03-25T08:35:13.591-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'category' → 4 synonym(s)
    //2026-03-25T08:35:13.592-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'transaction' → 4 synonym(s)
    //2026-03-25T08:35:13.592-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'revenue' → 4 synonym(s)
    //2026-03-25T08:35:13.592-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'headcount' → 4 synonym(s)
    //2026-03-25T08:35:13.592-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'recent orders' → 4 synonym(s)
    //2026-03-25T08:35:13.592-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'inventory' → 4 synonym(s)
    //2026-03-25T08:35:13.592-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    : Semantic alias groups loaded from supportingFiles/semantic.json — 17 groups
    //2026-03-25T08:35:13.595-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.s.MetadataDirectoryResolver      : MetadataDirectoryResolver ready — default dir: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/metadata, supportingFiles: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles
    //2026-03-25T08:35:13.599-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : ═══ RAG pipeline startup ═══
    //2026-03-25T08:35:13.599-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Root schema exists at '/Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/root/root_ecommerce_DBSchema.json' — initialising RAG from file…
    //2026-03-25T08:35:13.599-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.s.MetadataDirectoryResolver      : Latest schema filename registered — database='ecommerce' → file='root_ecommerce_DBSchema.json'
    //2026-03-25T08:35:13.599-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.service.SchemaExtractionService  : Building activeSchema from JSON file for database 'ecommerce'
    //2026-03-25T08:35:13.599-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.service.SchemaFileReaderService  : Using root ecommerce schema file: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/root/root_ecommerce_DBSchema.json
    //2026-03-25T08:35:13.616-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.service.SchemaFileReaderService  : Loaded schema file: root_ecommerce_DBSchema.json
    //2026-03-25T08:35:13.617-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.service.SchemaExtractionService  : buildActiveSchemaFromJson: 'ecommerce' — 9 table(s) loaded from JSON (business_context preserved for all tables)
    //2026-03-25T08:35:13.617-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'attributes.name' → 'name'
    //2026-03-25T08:35:13.618-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'attributes.unit' → 'unit'
    //2026-03-25T08:35:13.618-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'categories.name' → 'name'
    //2026-03-25T08:35:13.619-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'customers.name' → 'name'
    //2026-03-25T08:35:13.619-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'products.name' → 'name'
    //2026-03-25T08:35:13.620-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'products.sku' → 'sku'
    //2026-03-25T08:35:13.620-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'suppliers.name' → 'name'
    //2026-03-25T08:35:13.620-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  : Schema enrichment complete — 0 tables enriched, 7 columns enriched (strategy=AUTO)
    //2026-03-25T08:35:13.621-05:00  INFO 53137 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : Table 'attributes' identified as EAV (signals=6) — entityId='attributeid', attrName='name', attrValue='datatype'
    //2026-03-25T08:35:13.621-05:00  INFO 53137 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : Table 'productattributevalues' identified as EAV (signals=4) — entityId='attributeid', attrName='attribute_name', attrValue='value'
    //2026-03-25T08:35:13.622-05:00  INFO 53137 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : EAV detection complete — 2 EAV/vertical table(s) identified in schema 'ecommerce'
    //2026-03-25T08:35:13.624-05:00 DEBUG 53137 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : EAV table 'attributes' has no known_attributes catalogued — only TABLE/COLUMN chunks produced
    //2026-03-25T08:35:13.625-05:00 DEBUG 53137 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : EAV table 'productattributevalues' has no known_attributes catalogued — only TABLE/COLUMN chunks produced
    //2026-03-25T08:35:13.626-05:00  INFO 53137 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : Chunking complete: 76 metadata-enriched chunks from schema 'ecommerce' (chunkSize=80, stride=40, overlap=40)
    //2026-03-25T08:35:13.626-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Ecommerce schema loaded from file: 9 tables, 76 chunks
    //2026-03-25T08:35:13.627-05:00  INFO 53137 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Indexing 76 metadata-enriched chunks for database 'ecommerce'...
    //2026-03-25T08:35:35.332-05:00  INFO 53137 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Indexed 76 chunks (76 with embeddings, 0 keyword-only) into vector store
    //2026-03-25T08:35:35.332-05:00  INFO 53137 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Semantic search ENABLED — embedding model active
    //2026-03-25T08:35:35.396-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.s.VectorIndexPersistenceService  : Vector index persisted → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/vector_index_ecommerce.json (76 chunks)
    //2026-03-25T08:35:35.397-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Vector store ready — 76 entries indexed (SEMANTIC mode) user='root'
    //2026-03-25T08:35:35.397-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : ═══ RAG pipeline ready (ecommerce from file) ═══
    //2026-03-25T08:35:35.415-05:00  INFO 53137 --- [genQry] [           main] com.nlp.rag.seek.config.RedisConfig      : Redis connection factory configured → localhost:6379
    //2026-03-25T08:35:35.475-05:00  INFO 53137 --- [genQry] [           main] com.nlp.rag.seek.config.RedisConfig      : RedisTemplate<String, CacheEntry> configured with Jackson JSON serializer
    //2026-03-25T08:35:35.483-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.service.FernetEncryptionService  : Fernet: loaded key from configuration
    //2026-03-25T08:35:35.485-05:00  INFO 53137 --- [genQry] [           main] c.n.r.seek.service.SQLGenerationService  : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=1102 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T08:35:35.898-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.doc.service.DocumentRagService   : Doc business rules loaded from supportingFiles/BusinessRulesForPrompts.json — 571 chars
    //2026-03-25T08:35:35.930-05:00  INFO 53137 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → SECONDARY (ecommerce) reachable
    //2026-03-25T08:35:35.979-05:00  WARN 53137 --- [genQry] [           main] .s.s.UserDetailsServiceAutoConfiguration :
    //
    //Using generated security password: 37fb83f2-ed2a-4cc9-85d1-284d6b8fd018
    //
    //This generated password is for development use only. Your security configuration must be updated before running your application in production.
    //
    //2026-03-25T08:35:36.361-05:00  INFO 53137 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Started SeekApplication in 24.664 seconds (process running for 24.967)
    //2026-03-25T08:35:36.363-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : Running genQry DB migrations…
    //2026-03-25T08:35:36.420-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : Migration step (skipped): StatementCallback; bad SQL grammar [CREATE INDEX IF NOT EXISTS idx_transcript_session  ON transcript_details(session_id)]
    //2026-03-25T08:35:36.424-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : business_rules table already seeded (35 rows) — skipping
    //2026-03-25T08:35:36.425-05:00 DEBUG 53137 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : DATA_NOT_AVAILABLE rule already exists — skipping
    //2026-03-25T08:35:36.425-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : genQry DB migrations complete ✅
    //2026-03-25T08:35:36.432-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : BusinessRulesForPrompts.json exported — 35 rules → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/BusinessRulesForPrompts.json
    //2026-03-25T08:35:36.433-05:00  INFO 53137 --- [genQry] [           main] c.n.r.seek.service.SQLGenerationService  : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=1102 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T08:35:36.433-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : SQLGenerationService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T08:35:36.433-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.service.SQLExplanationService    : Explanation rules loaded from BusinessRulesForPrompts.json — 321 chars
    //2026-03-25T08:35:36.433-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : SQLExplanationService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T08:35:36.433-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.doc.service.DocumentRagService   : Doc business rules loaded from supportingFiles/BusinessRulesForPrompts.json — 571 chars
    //2026-03-25T08:35:36.433-05:00  INFO 53137 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : DocumentRagService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T08:35:50.237-05:00  INFO 53137 --- [genQry] [nio-9095-exec-1] c.n.rag.seek.controller.AuthController   : POST /api/v1/auth/token — username='AshokSekar'
    //2026-03-25T08:35:50.354-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-1] c.nlp.rag.seek.config.JwtTokenProvider   : JWT generated for email='mailashoky@gmail.com' username='AshokSekar' userType='Root' expires=Thu Mar 26 08:35:50 CDT 2026
    //2026-03-25T08:35:50.359-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-3] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:35:50.359-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-2] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:35:50.367-05:00  INFO 53137 --- [genQry] [nio-9095-exec-2] c.n.r.s.controller.WorkspaceController   : GET /api/v1/workSpace — username='AshokSekar' usertype='Root'
    //2026-03-25T08:35:50.367-05:00  INFO 53137 --- [genQry] [nio-9095-exec-3] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T08:35:50.369-05:00  INFO 53137 --- [genQry] [nio-9095-exec-2] c.n.r.s.controller.WorkspaceController   : Workspace: user='AshokSekar' usertype='Root' isGuest=false → fileList=[{type=database, name=ecommerce}, {type=document, name=2021.pdf}, {type=document, name=2023.pdf}, {type=document, name=AshokkumarSeka.docx}, {type=document, name=AshokW2.pdf}]
    //2026-03-25T08:35:50.380-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-3] c.n.r.s.c.QueryHistoryController         : Loaded 56 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T08:35:56.757-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:35:56.761-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg in decimal
    //2026-03-25T08:35:56.762-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T08:35:56.762-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T08:35:56.762-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T08:35:56.762-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg in decimal' | db='ecommerce' | topK=3
    //2026-03-25T08:35:56.763-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T08:35:56.763-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:35:56.840-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:35:56.840-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SemanticCacheService  : Tier-1 MISS key='genqry:cache:ecommerce:norm:ef3d36489796e229'  canonical='get 1 decimal heavier kg product than'
    //2026-03-25T08:35:57.125-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SemanticCacheService  : Tier-2 MISS — no vector entries for db='ecommerce'
    //2026-03-25T08:35:57.359-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.VectorStoreService  : Semantic search 'List all the products which is heavier than 1 kg in decimal' → top 3 results:
    //2026-03-25T08:35:57.359-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.31429866882390645 | table=orderdetails#0 col=orderdetails
    //2026-03-25T08:35:57.359-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2958346761567303 | table=products#0 col=products
    //2026-03-25T08:35:57.359-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.28478200806337844 | table=productsuppliers.productid#0 col=productsuppliers
    //2026-03-25T08:35:57.360-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : FK-expansion: injected table 'orders' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:35:57.360-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : FK-expansion: injected table 'categories' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:35:57.360-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : FK-expansion: injected table 'suppliers' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:35:57.361-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : EAV-injection: force-injected EAV table 'attributes' for query terms [weight]
    //2026-03-25T08:35:57.361-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : EAV-injection: force-injected EAV table 'productattributevalues' for query terms [weight]
    //2026-03-25T08:35:57.361-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : FK-expansion: injected table 'customers' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers, orders, categories, suppliers, attributes, productattributevalues])
    //2026-03-25T08:35:57.362-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : TopK cap applied: 9 chunks → 3 semantic + 6 injected(FK/EAV) = 9 total (topK=3)
    //2026-03-25T08:35:57.363-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping orderdetails#0 [score=0.314] for table 'orderdetails'
    //2026-03-25T08:35:57.364-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping products#0 [score=0.296] for table 'products'
    //2026-03-25T08:35:57.364-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping productsuppliers.productid#0 [score=0.285] for table 'productsuppliers'
    //2026-03-25T08:35:57.364-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping orders#0 [score=0.000] for table 'orders'
    //2026-03-25T08:35:57.364-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping categories#0 [score=0.000] for table 'categories'
    //2026-03-25T08:35:57.364-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping suppliers#0 [score=0.000] for table 'suppliers'
    //2026-03-25T08:35:57.364-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping attributes#0 [score=0.500] for table 'attributes'
    //2026-03-25T08:35:57.364-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping productattributevalues#0 [score=0.500] for table 'productattributevalues'
    //2026-03-25T08:35:57.364-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping customers#0 [score=0.000] for table 'customers'
    //2026-03-25T08:35:57.364-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Sending sanitized query to LLM with top-9 schema chunks
    //2026-03-25T08:35:57.367-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : ══════════════ PROMPT SENT TO LLM ══════════════
    //You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query..
    //
    //Rules:
    //1. Use ONLY the tables and columns that appear in the schema context below.
    //2. Write standard SQL compatible with PostgreSQL.
    //3. Use explicit JOINs with ON clauses — never implicit comma joins.
    //4. Add a WHERE clause whenever a filter is implied.
    //5. Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) when appropriate.
    //6. Add ORDER BY when a ranking or 'top N' is implied.
    //7. Return ONLY the SQL statement — no prose, no markdown fences.
    //8. CRITICAL JOIN RULE — if two tables doesnt have foreign key relationship then dont join them
    //9. If the user's question refers to a concept, entity, or table that does NOT exist anywhere in the provided schema context, do NOT guess or map it to the closest table. Instead respond with exactly: DATA_NOT_AVAILABLE: The database schema does not contain a table or concept matching '<user term>'. Available tables are: <list the table names from the schema context>.
    //10. MPORTANT: ALL tables and columns listed in the schema context ARE valid and exist in the database — you MUST use them if they match the user's question
    //11. End the sql statement with a semicolon.
    //
    //Follow the below rules for EAV tables:
    //1. NEVER use attribute names (e.g. Weight, Color, Size) as SQL column names.
    //2. Use WHERE <attr_name_col> = '<key>' to select a specific attribute.
    //3. Use CAST(<attr_value_col> AS <type>) when reading numeric or date values.
    //4. For multiple attributes in one row, use conditional aggregation: MAX(CASE WHEN <attr_name_col> = '<key1>' THEN CAST(<attr_value_col> AS <type>) END) AS <key1>
    //5. Join to the entity table via the entity_id column shown in the context.
    //6. EAV attributes retrieved for this query are listed in the schema context — use their FILTER and CAST HINT values exactly.
    //7. Attribute names are stored as ROW VALUES in the attribute name column.
    //8. Join the entity table to the EAV data table on the entity ID column.
    //9. Join the EAV data table to the attributes table on the attribute ID column.
    //
    //Database: ecommerce
    //
    //IMPORTANT: ALL tables listed below are valid and exist in the database. You MUST use any table or column from the schema context if it is relevant to the question. Only respond with DATA_NOT_AVAILABLE if the user's question refers to a concept that has NO matching table or column ANYWHERE in the schema context below.
    //
    //--- RETRIEVED SCHEMA CHUNKS ---
    //• orderdetails#0
    //  TABLE   : orderdetails
    //  COLUMNS : orderdetailid:serial, orderid:integer, productid:integer, quantity:integer, unitprice:numeric, linetotal:numeric
    //  PKs     : orderdetailid
    //  FKs     : orderid->orders.orderid, productid->products.productid
    //  CONTEXT : Line items for each order. Each row represents one product in an order.
    //  DESC    : Line items for each order. Each row represents one product in an order.
    //  TEXT    : Table: orderdetails. Description: Line items for each order. Each row represents one product in an order.. Columns: orderdetailid (serial) [PK]; orderid (integer) [FK -> orders.orderid]; productid (integer) [FK -> products.productid]; quantity (integer); unitprice (numeric); linetotal (numeric); Column descriptions: orderdetailid: orderdetailid (serial). orderid: orderid (integer). productid: productid (integer). quantity: quantity (integer). unitprice: unitprice (numeric). linetotal: linetotal (numeric).
    //
    //• products#0
    //  TABLE   : products
    //  COLUMNS : productid:serial, name:character varying, description:text, categoryid:integer, price:numeric, stockqty:integer, sku:character varying, isactive:boolean, createdat:timestamp, updatedat:timestamp
    //  PKs     : productid
    //  FKs     : categoryid->categories.categoryid
    //  CONTEXT : Master product catalogue. Each product belongs to one category and may have many EAV attributes.
    //  DESC    : Master product catalogue. Each product belongs to one category and may have many EAV attributes.
    //  TEXT    : Table: products. Description: Master product catalogue. Each product belongs to one category and may have many EAV attributes.. Columns: productid (serial) [PK]; name (character varying); description (text); categoryid (integer) [FK -> categories.categoryid]; price (numeric); stockqty (integer); sku (character varying); isactive (boolean); createdat (timestamp); updatedat (timestamp); Column descriptions: productid: productid (serial). name: Name — the name of the products. Original column name: name.. description: description (text). categoryid: categoryid (integer). price: price (numeric). stockqty: stockqty (integer). sku: Sku — the sku of
    //
    //• productsuppliers.productid#0
    //  TABLE   : productsuppliers
    //  COLUMN  : productid
    //  TYPE    : integer
    //  ROLE    : PRIMARY KEY
    //  FK REF  : products.productid
    //  DESC    : productid (integer)
    //  TEXT    : Column: productid. Data type: integer. Description: productid (integer). Role: primary key — uniquely identifies each row. Role: foreign key referencing products.productid. Nullable: no.
    //
    //• orders#0
    //  TABLE   : orders
    //  COLUMNS : orderid:serial, customerid:integer, orderdate:date, totalamount:numeric, status:character varying, createdat:timestamp, updatedat:timestamp
    //  PKs     : orderid
    //  FKs     : customerid->customers.customerid
    //  CONTEXT : Records each customer order. TotalAmount is the sum of all OrderDetails lines.
    //  DESC    : Records each customer order. TotalAmount is the sum of all OrderDetails lines.
    //  TEXT    : Table: orders. Description: Records each customer order. TotalAmount is the sum of all OrderDetails lines.. Columns: orderid (serial) [PK]; customerid (integer) [FK -> customers.customerid]; orderdate (date); totalamount (numeric); status (character varying); createdat (timestamp); updatedat (timestamp); Column descriptions: orderid: orderid (serial). customerid: customerid (integer). orderdate: orderdate (date). totalamount: totalamount (numeric). status: status (character varying). createdat: createdat (timestamp). updatedat: updatedat (timestamp).
    //
    //• categories#0
    //  TABLE   : categories
    //  COLUMNS : categoryid:serial, name:character varying, description:text, createdat:timestamp
    //  PKs     : categoryid
    //  CONTEXT : Product categories used to group and filter products.
    //  DESC    : Product categories used to group and filter products.
    //  TEXT    : Table: categories. Description: Product categories used to group and filter products.. Columns: categoryid (serial) [PK]; name (character varying); description (text); createdat (timestamp); Column descriptions: categoryid: categoryid (serial). name: Name — the name of the categories. Original column name: name.. description: description (text). createdat: createdat (timestamp).
    //
    //• suppliers#0
    //  TABLE   : suppliers
    //  COLUMNS : supplierid:serial, name:character varying, contactinfo:text, email:character varying, phone:character varying, address:character varying, createdat:timestamp, updatedat:timestamp
    //  PKs     : supplierid
    //  CONTEXT : Suppliers who provide products. One supplier can supply many products; one product can have many suppliers.
    //  DESC    : Suppliers who provide products. One supplier can supply many products; one product can have many suppliers.
    //  TEXT    : Table: suppliers. Description: Suppliers who provide products. One supplier can supply many products; one product can have many suppliers.. Columns: supplierid (serial) [PK]; name (character varying); contactinfo (text); email (character varying); phone (character varying); address (character varying); createdat (timestamp); updatedat (timestamp); Column descriptions: supplierid: supplierid (serial). name: Name — the name of the suppliers. Original column name: name.. contactinfo: contactinfo (text). email: email (character varying). phone: phone (character varying). address: address (character varying). createdat: createdat (timestamp). updatedat: updatedat (timestamp).
    //
    //• attributes#0
    //  TABLE         : attributes
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : name
    //  ATTR VALUE COL: datatype
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.
    //  TEXT          : Table: attributes. Description: EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE name = '<attribute_key>' to filter, and read datatype for the value. Entity linked via attributeid. Columns: attributeid (serial) [PK]; name (character varying); datatype (character varying); unit (character varying); description (text); createdat (timestamp); Column descriptions: attributeid:
    //
    //• productattributevalues#0
    //  TABLE         : productattributevalues
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : attribute_name
    //  ATTR VALUE COL: value
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE attribute_name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.
    //  TEXT          : Table: productattributevalues. Description: EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE attribute_name = '<attribute_key>' to filter, and read value for the value. Entity linked via attributeid
    //
    //• customers#0
    //  TABLE   : customers
    //  COLUMNS : customerid:serial, name:character varying, email:character varying, address:character varying, createdat:timestamp, updatedat:timestamp
    //  PKs     : customerid
    //  CONTEXT : Stores customer details. Each customer can place many orders.
    //  DESC    : Stores customer details. Each customer can place many orders.
    //  TEXT    : Table: customers. Description: Stores customer details. Each customer can place many orders.. Columns: customerid (serial) [PK]; name (character varying); email (character varying); address (character varying); createdat (timestamp); updatedat (timestamp); Column descriptions: customerid: customerid (serial). name: Name — the name of the customers. Original column name: name.. email: email (character varying). address: address (character varying). createdat: createdat (timestamp). updatedat: updatedat (timestamp).
    //--- END OF SCHEMA CHUNKS ---
    //
    //Natural-language question: List all the products which is heavier than 1 kg in decimal
    //
    //SQL:
    //
    //══════════════ END OF PROMPT ══════════════
    //2026-03-25T08:35:59.031-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : ══════════════ LLM RESPONSE ══════════════
    //DATA_NOT_AVAILABLE: The database schema does not contain a table or concept matching 'heavier than 1 kg'. Available tables are: orderdetails, products, productsuppliers, orders, categories, suppliers, attributes, productattributevalues, customers.
    //══════════════ END OF LLM RESPONSE ══════════════
    //2026-03-25T08:35:59.032-05:00  WARN 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : LLM signalled DATA_NOT_AVAILABLE for query='List all the products which is heavier than 1 kg in decimal': The database schema does not contain a table or concept matching 'heavier than 1 kg'. Available tables are: orderdetails, products, productsuppliers, orders, categories, suppliers, attributes, productattributevalues, customers.
    //2026-03-25T08:35:59.034-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.TranscriptService   : Error transcript appended → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts_03_25_26.json
    //2026-03-25T08:35:59.045-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.repository.UserRepository   : Transcript inserted | id=1087 | userId=1000 | status=FAILURE | dataSource=ecommerce
    //2026-03-25T08:35:59.057-05:00 DEBUG 53137 --- [genQry] [nio-9095-exec-4] com.nlp.rag.seek.service.UserDbService   : transcript_json JDBC type=org.postgresql.util.PGobject value-preview='{"remarks": "The requested data is not available in the database schema. The database schema does not contain a table or'
    //2026-03-25T08:35:59.066-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] com.nlp.rag.seek.service.UserDbService   : Exported 57 transcripts → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json for user='AshokSekar'
    //2026-03-25T08:35:59.067-05:00  INFO 53137 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=false | executed=false
    //^C%                                                                                                                                                                                                              ashoksekar@ashoks-MacBook-Pro seek % ./mvnw clean package -DskipTests -q && java -jar target/genQry-0.0.1-SNAPSHOT.jar
    //
    //  .   ____          _            __ _ _
    // /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
    //( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
    // \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
    //  '  |____| .__|_| |_|_| |_\__, | / / / /
    // =========|_|==============|___/=/_/_/_/
    // :: Spring Boot ::                (v3.2.5)
    //
    //2026-03-25T08:39:05.926-05:00  INFO 53405 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Starting SeekApplication v0.0.1-SNAPSHOT using Java 17.0.18 with PID 53405 (/Users/ashoksekar/workspace/genAi/seek/target/genQry-0.0.1-SNAPSHOT.jar started by ashoksekar in /Users/ashoksekar/workspace/genAi/seek)
    //2026-03-25T08:39:05.930-05:00 DEBUG 53405 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Running with Spring Boot v3.2.5, Spring v6.1.6
    //2026-03-25T08:39:05.930-05:00  INFO 53405 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : No active profile set, falling back to 1 default profile: "default"
    //2026-03-25T08:39:07.212-05:00  INFO 53405 --- [genQry] [           main] c.nlp.rag.seek.config.JwtTokenProvider   : JwtTokenProvider initialised — expiration=86400000ms
    //2026-03-25T08:39:07.234-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.config.JwtAuthenticationFilter   : Filter 'jwtAuthenticationFilter' configured for use
    //2026-03-25T08:39:07.329-05:00  INFO 53405 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → PRIMARY (genQry) set to read-only mode
    //2026-03-25T08:39:07.332-05:00  INFO 53405 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → PRIMARY (genQry) reachable
    //2026-03-25T08:39:07.668-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.service.SQLExplanationService    : Explanation rules loaded from BusinessRulesForPrompts.json — 321 chars
    //2026-03-25T08:39:07.679-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    : Abbreviations loaded from supportingFiles/abbreviations.json — 152 entries across all sections
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'employee' → 8 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'customer' → 5 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'order' → 5 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'product' → 5 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'department' → 5 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'invoice' → 4 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'payment' → 5 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'salary' → 5 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'address' → 4 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'account' → 4 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'vendor' → 4 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'category' → 4 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'transaction' → 4 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'revenue' → 4 synonym(s)
    //2026-03-25T08:39:07.680-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'headcount' → 4 synonym(s)
    //2026-03-25T08:39:07.681-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'recent orders' → 4 synonym(s)
    //2026-03-25T08:39:07.681-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'inventory' → 4 synonym(s)
    //2026-03-25T08:39:07.681-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    : Semantic alias groups loaded from supportingFiles/semantic.json — 17 groups
    //2026-03-25T08:39:07.685-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.s.MetadataDirectoryResolver      : MetadataDirectoryResolver ready — default dir: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/metadata, supportingFiles: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles
    //2026-03-25T08:39:07.688-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : ═══ RAG pipeline startup ═══
    //2026-03-25T08:39:07.688-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Root schema exists at '/Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/root/root_ecommerce_DBSchema.json' — initialising RAG from file…
    //2026-03-25T08:39:07.689-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.s.MetadataDirectoryResolver      : Latest schema filename registered — database='ecommerce' → file='root_ecommerce_DBSchema.json'
    //2026-03-25T08:39:07.689-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.service.SchemaExtractionService  : Building activeSchema from JSON file for database 'ecommerce'
    //2026-03-25T08:39:07.689-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.service.SchemaFileReaderService  : Using root ecommerce schema file: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/root/root_ecommerce_DBSchema.json
    //2026-03-25T08:39:07.703-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.service.SchemaFileReaderService  : Loaded schema file: root_ecommerce_DBSchema.json
    //2026-03-25T08:39:07.704-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.service.SchemaExtractionService  : buildActiveSchemaFromJson: 'ecommerce' — 9 table(s) loaded from JSON (business_context preserved for all tables)
    //2026-03-25T08:39:07.704-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'attributes.name' → 'name'
    //2026-03-25T08:39:07.705-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'attributes.unit' → 'unit'
    //2026-03-25T08:39:07.705-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'categories.name' → 'name'
    //2026-03-25T08:39:07.705-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'customers.name' → 'name'
    //2026-03-25T08:39:07.706-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'products.name' → 'name'
    //2026-03-25T08:39:07.707-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'products.sku' → 'sku'
    //2026-03-25T08:39:07.707-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'suppliers.name' → 'name'
    //2026-03-25T08:39:07.707-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  : Schema enrichment complete — 0 tables enriched, 7 columns enriched (strategy=AUTO)
    //2026-03-25T08:39:07.708-05:00  INFO 53405 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : Table 'attributes' identified as EAV (signals=6) — entityId='attributeid', attrName='name', attrValue='datatype'
    //2026-03-25T08:39:07.709-05:00  INFO 53405 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : Table 'productattributevalues' identified as EAV (signals=4) — entityId='attributeid', attrName='attribute_name', attrValue='value'
    //2026-03-25T08:39:07.709-05:00  INFO 53405 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : EAV detection complete — 2 EAV/vertical table(s) identified in schema 'ecommerce'
    //2026-03-25T08:39:07.710-05:00 DEBUG 53405 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : EAV table 'attributes' has no known_attributes catalogued — only TABLE/COLUMN chunks produced
    //2026-03-25T08:39:07.712-05:00 DEBUG 53405 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : EAV table 'productattributevalues' has no known_attributes catalogued — only TABLE/COLUMN chunks produced
    //2026-03-25T08:39:07.713-05:00  INFO 53405 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : Chunking complete: 76 metadata-enriched chunks from schema 'ecommerce' (chunkSize=80, stride=40, overlap=40)
    //2026-03-25T08:39:07.713-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Ecommerce schema loaded from file: 9 tables, 76 chunks
    //2026-03-25T08:39:07.714-05:00  INFO 53405 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Indexing 76 metadata-enriched chunks for database 'ecommerce'...
    //2026-03-25T08:39:22.308-05:00  INFO 53405 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Indexed 76 chunks (76 with embeddings, 0 keyword-only) into vector store
    //2026-03-25T08:39:22.309-05:00  INFO 53405 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Semantic search ENABLED — embedding model active
    //2026-03-25T08:39:22.375-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.s.VectorIndexPersistenceService  : Vector index persisted → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/vector_index_ecommerce.json (76 chunks)
    //2026-03-25T08:39:22.376-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Vector store ready — 76 entries indexed (SEMANTIC mode) user='root'
    //2026-03-25T08:39:22.376-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : ═══ RAG pipeline ready (ecommerce from file) ═══
    //2026-03-25T08:39:22.394-05:00  INFO 53405 --- [genQry] [           main] com.nlp.rag.seek.config.RedisConfig      : Redis connection factory configured → localhost:6379
    //2026-03-25T08:39:22.457-05:00  INFO 53405 --- [genQry] [           main] com.nlp.rag.seek.config.RedisConfig      : RedisTemplate<String, CacheEntry> configured with Jackson JSON serializer
    //2026-03-25T08:39:22.466-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.service.FernetEncryptionService  : Fernet: loaded key from configuration
    //2026-03-25T08:39:22.468-05:00  INFO 53405 --- [genQry] [           main] c.n.r.seek.service.SQLGenerationService  : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=1102 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T08:39:22.896-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.doc.service.DocumentRagService   : Doc business rules loaded from supportingFiles/BusinessRulesForPrompts.json — 571 chars
    //2026-03-25T08:39:22.929-05:00  INFO 53405 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → SECONDARY (ecommerce) reachable
    //2026-03-25T08:39:22.976-05:00  WARN 53405 --- [genQry] [           main] .s.s.UserDetailsServiceAutoConfiguration :
    //
    //Using generated security password: 1cee898f-18ce-4b53-b258-fbe817665f17
    //
    //This generated password is for development use only. Your security configuration must be updated before running your application in production.
    //
    //2026-03-25T08:39:23.359-05:00  INFO 53405 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Started SeekApplication in 17.675 seconds (process running for 17.988)
    //2026-03-25T08:39:23.360-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : Running genQry DB migrations…
    //2026-03-25T08:39:23.412-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : Migration step (skipped): StatementCallback; bad SQL grammar [CREATE INDEX IF NOT EXISTS idx_transcript_session  ON transcript_details(session_id)]
    //2026-03-25T08:39:23.415-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : business_rules table already seeded (35 rows) — skipping
    //2026-03-25T08:39:23.416-05:00 DEBUG 53405 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : DATA_NOT_AVAILABLE rule already exists — skipping
    //2026-03-25T08:39:23.416-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : genQry DB migrations complete ✅
    //2026-03-25T08:39:23.422-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : BusinessRulesForPrompts.json exported — 35 rules → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/BusinessRulesForPrompts.json
    //2026-03-25T08:39:23.423-05:00  INFO 53405 --- [genQry] [           main] c.n.r.seek.service.SQLGenerationService  : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=732 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T08:39:23.423-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : SQLGenerationService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T08:39:23.423-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.service.SQLExplanationService    : Explanation rules loaded from BusinessRulesForPrompts.json — 321 chars
    //2026-03-25T08:39:23.423-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : SQLExplanationService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T08:39:23.423-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.doc.service.DocumentRagService   : Doc business rules loaded from supportingFiles/BusinessRulesForPrompts.json — 571 chars
    //2026-03-25T08:39:23.423-05:00  INFO 53405 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : DocumentRagService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T08:39:47.513-05:00  INFO 53405 --- [genQry] [nio-9095-exec-3] c.n.rag.seek.controller.AuthController   : POST /api/v1/auth/token — username='AshokSekar'
    //2026-03-25T08:39:47.585-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-3] c.nlp.rag.seek.config.JwtTokenProvider   : JWT generated for email='mailashoky@gmail.com' username='AshokSekar' userType='Root' expires=Thu Mar 26 08:39:47 CDT 2026
    //2026-03-25T08:39:47.589-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-1] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:39:47.589-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-2] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:39:47.598-05:00  INFO 53405 --- [genQry] [nio-9095-exec-2] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T08:39:47.598-05:00  INFO 53405 --- [genQry] [nio-9095-exec-1] c.n.r.s.controller.WorkspaceController   : GET /api/v1/workSpace — username='AshokSekar' usertype='Root'
    //2026-03-25T08:39:47.601-05:00  INFO 53405 --- [genQry] [nio-9095-exec-1] c.n.r.s.controller.WorkspaceController   : Workspace: user='AshokSekar' usertype='Root' isGuest=false → fileList=[{type=database, name=ecommerce}, {type=document, name=2021.pdf}, {type=document, name=2023.pdf}, {type=document, name=AshokkumarSeka.docx}, {type=document, name=AshokW2.pdf}]
    //2026-03-25T08:39:47.614-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-2] c.n.r.s.c.QueryHistoryController         : Loaded 57 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T08:39:50.052-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:39:50.058-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg in decimal
    //2026-03-25T08:39:50.058-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T08:39:50.058-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T08:39:50.058-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T08:39:50.058-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg in decimal' | db='ecommerce' | topK=3
    //2026-03-25T08:39:50.059-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T08:39:50.059-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:39:50.131-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:39:50.131-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SemanticCacheService  : Tier-1 MISS key='genqry:cache:ecommerce:norm:ef3d36489796e229'  canonical='get 1 decimal heavier kg product than'
    //2026-03-25T08:39:50.254-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SemanticCacheService  : Tier-2 MISS — no vector entries for db='ecommerce'
    //2026-03-25T08:39:50.392-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.VectorStoreService  : Semantic search 'List all the products which is heavier than 1 kg in decimal' → top 3 results:
    //2026-03-25T08:39:50.392-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.3142740134652706 | table=orderdetails#0 col=orderdetails
    //2026-03-25T08:39:50.392-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.29582178666732983 | table=products#0 col=products
    //2026-03-25T08:39:50.392-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.28472708199602886 | table=productsuppliers.productid#0 col=productsuppliers
    //2026-03-25T08:39:50.393-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : FK-expansion: injected table 'orders' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:39:50.393-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : FK-expansion: injected table 'categories' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:39:50.393-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : FK-expansion: injected table 'suppliers' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:39:50.394-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : EAV-injection: force-injected EAV table 'attributes' for query terms [weight]
    //2026-03-25T08:39:50.394-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : EAV-injection: force-injected EAV table 'productattributevalues' for query terms [weight]
    //2026-03-25T08:39:50.395-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : TopK cap applied: 8 chunks → 3 (topK=3)
    //2026-03-25T08:39:50.396-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping attributes#0 [score=0.500] for table 'attributes'
    //2026-03-25T08:39:50.396-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping productattributevalues#0 [score=0.500] for table 'productattributevalues'
    //2026-03-25T08:39:50.396-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping orderdetails#0 [score=0.314] for table 'orderdetails'
    //2026-03-25T08:39:50.396-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : Sending sanitized query to LLM with top-3 schema chunks
    //2026-03-25T08:39:50.400-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : ══════════════ PROMPT SENT TO LLM ══════════════
    //You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query..
    //
    //Rules:
    //1. Use ONLY the tables and columns that appear in the schema context below.
    //2. Write standard SQL compatible with PostgreSQL.
    //3. Use explicit JOINs with ON clauses — never implicit comma joins.
    //4. Add a WHERE clause whenever a filter is implied.
    //5. Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) when appropriate.
    //6. Add ORDER BY when a ranking or 'top N' is implied.
    //7. Return ONLY the SQL statement — no prose, no markdown fences.
    //8. CRITICAL JOIN RULE — if two tables doesnt have foreign key relationship then dont join them
    //10. MPORTANT: ALL tables and columns listed in the schema context ARE valid and exist in the database — you MUST use them if they match the user's question
    //11. End the sql statement with a semicolon.
    //
    //Follow the below rules for EAV tables:
    //1. NEVER use attribute names (e.g. Weight, Color, Size) as SQL column names.
    //2. Use WHERE <attr_name_col> = '<key>' to select a specific attribute.
    //3. Use CAST(<attr_value_col> AS <type>) when reading numeric or date values.
    //4. For multiple attributes in one row, use conditional aggregation: MAX(CASE WHEN <attr_name_col> = '<key1>' THEN CAST(<attr_value_col> AS <type>) END) AS <key1>
    //5. Join to the entity table via the entity_id column shown in the context.
    //6. EAV attributes retrieved for this query are listed in the schema context — use their FILTER and CAST HINT values exactly.
    //7. Attribute names are stored as ROW VALUES in the attribute name column.
    //8. Join the entity table to the EAV data table on the entity ID column.
    //9. Join the EAV data table to the attributes table on the attribute ID column.
    //
    //Database: ecommerce
    //
    //IMPORTANT: ALL tables listed below are valid and exist in the database. You MUST use any table or column from the schema context if it is relevant to the question. Only respond with DATA_NOT_AVAILABLE if the user's question refers to a concept that has NO matching table or column ANYWHERE in the schema context below.
    //
    //--- RETRIEVED SCHEMA CHUNKS ---
    //• attributes#0
    //  TABLE         : attributes
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : name
    //  ATTR VALUE COL: datatype
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.
    //  TEXT          : Table: attributes. Description: EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE name = '<attribute_key>' to filter, and read datatype for the value. Entity linked via attributeid. Columns: attributeid (serial) [PK]; name (character varying); datatype (character varying); unit (character varying); description (text); createdat (timestamp); Column descriptions: attributeid:
    //
    //• productattributevalues#0
    //  TABLE         : productattributevalues
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : attribute_name
    //  ATTR VALUE COL: value
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE attribute_name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.
    //  TEXT          : Table: productattributevalues. Description: EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE attribute_name = '<attribute_key>' to filter, and read value for the value. Entity linked via attributeid
    //
    //• orderdetails#0
    //  TABLE   : orderdetails
    //  COLUMNS : orderdetailid:serial, orderid:integer, productid:integer, quantity:integer, unitprice:numeric, linetotal:numeric
    //  PKs     : orderdetailid
    //  FKs     : orderid->orders.orderid, productid->products.productid
    //  CONTEXT : Line items for each order. Each row represents one product in an order.
    //  DESC    : Line items for each order. Each row represents one product in an order.
    //  TEXT    : Table: orderdetails. Description: Line items for each order. Each row represents one product in an order.. Columns: orderdetailid (serial) [PK]; orderid (integer) [FK -> orders.orderid]; productid (integer) [FK -> products.productid]; quantity (integer); unitprice (numeric); linetotal (numeric); Column descriptions: orderdetailid: orderdetailid (serial). orderid: orderid (integer). productid: productid (integer). quantity: quantity (integer). unitprice: unitprice (numeric). linetotal: linetotal (numeric).
    //--- END OF SCHEMA CHUNKS ---
    //
    //Natural-language question: List all the products which is heavier than 1 kg in decimal
    //
    //SQL:
    //
    //══════════════ END OF PROMPT ══════════════
    //2026-03-25T08:39:51.783-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : ══════════════ LLM RESPONSE ══════════════
    //DATA_NOT_AVAILABLE
    //══════════════ END OF LLM RESPONSE ══════════════
    //2026-03-25T08:39:51.784-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : ◀ LLM generated SQL:
    //══════════════════════════════
    //DATA_NOT_AVAILABLE
    //══════════════════════════════
    //2026-03-25T08:39:51.784-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLValidationService  : SQL validation: valid=false, errors=[SQL does not start with a recognised keyword (SELECT/INSERT/UPDATE/DELETE/WITH).], warnings=[SQL does not end with a semicolon.]
    //2026-03-25T08:39:51.788-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.ConfidenceScoreService   : Confidence: semantic=0.438(×0.40) | coverage=0.667(×0.20) | validity=0.0(×0.20) | tableMatch=0.000(×0.20) | → total=0.309 [LOW]
    //2026-03-25T08:39:51.788-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION PROMPT SENT TO LLM ══════════════
    //1. You are a helpful data analyst. Given the SQL query below, write a concise,
    //plain-English explanation (2–4 sentences) describing:
    //2. Which data is being retrieved or modified.
    //3. What filters or conditions apply.
    //4. How the results are ordered or grouped (if any).
    //5. The business purpose this query is likely serving.
    //
    //Do NOT include the SQL in your answer. Use simple language a non-technical
    //business user would understand.
    //
    //SQL:
    //DATA_NOT_AVAILABLE
    //══════════════ END OF EXPLANATION PROMPT ══════════════
    //2026-03-25T08:39:52.755-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION LLM RESPONSE ══════════════
    //I'm sorry, but I can't provide an explanation of the SQL query since the query itself is not available. If you can provide the SQL query, I'd be happy to help explain it in simple terms.
    //══════════════ END OF EXPLANATION LLM RESPONSE ══════════════
    //2026-03-25T08:39:52.756-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : ◀ NL→SQL pipeline done | valid=false | confidence=0.309 [LOW] | piiTokens=0
    //2026-03-25T08:39:52.764-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.TranscriptService   : Transcript appended → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts_03_25_26.json (3 entries total)
    //2026-03-25T08:39:52.767-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.TranscriptService   : request userName='AshokSekar' resolved to userId=1000
    //2026-03-25T08:39:52.783-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.repository.UserRepository   : Transcript inserted | id=1088 | userId=1000 | status=FAILURE | dataSource=ecommerce
    //2026-03-25T08:39:52.783-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.TranscriptService   : Transcript persisted to DB with id=1088
    //2026-03-25T08:39:52.793-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] com.nlp.rag.seek.service.UserDbService   : transcript_json JDBC type=org.postgresql.util.PGobject value-preview='{"remarks": "", "userPrompt": "List all the products which is heavier than 1 kg in decimal", "explanation": "I'm sorry, '
    //2026-03-25T08:39:52.802-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] com.nlp.rag.seek.service.UserDbService   : Exported 58 transcripts → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json for user='AshokSekar'
    //2026-03-25T08:39:52.803-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:39:53.012-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:39:53.038-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-1/norm] key='genqry:cache:ecommerce:norm:ef3d36489796e229' canonical='get 1 decimal heavier kg product than' query='List all the products which is heavier than 1 kg in decimal'
    //2026-03-25T08:39:53.040-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-2/vec] key='genqry:cache:ecommerce:vec:b13598e8-c832-4eb9-b572-ecf8ff9e9c5d' query='List all the products which is heavier than 1 kg in decimal'
    //2026-03-25T08:39:53.041-05:00  INFO 53405 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=false | executed=false
    //2026-03-25T08:39:53.059-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-5] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:39:53.060-05:00  INFO 53405 --- [genQry] [nio-9095-exec-5] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T08:39:53.062-05:00 DEBUG 53405 --- [genQry] [nio-9095-exec-5] c.n.r.s.c.QueryHistoryController         : Loaded 58 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //^C%                                                                                                                                                                                                              ashoksekar@ashoks-MacBook-Pro seek % ./mvnw clean package -DskipTests -q && java -jar target/genQry-0.0.1-SNAPSHOT.jar
    //
    //  .   ____          _            __ _ _
    // /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
    //( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
    // \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
    //  '  |____| .__|_| |_|_| |_\__, | / / / /
    // =========|_|==============|___/=/_/_/_/
    // :: Spring Boot ::                (v3.2.5)
    //
    //2026-03-25T08:49:10.279-05:00  INFO 53969 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Starting SeekApplication v0.0.1-SNAPSHOT using Java 17.0.18 with PID 53969 (/Users/ashoksekar/workspace/genAi/seek/target/genQry-0.0.1-SNAPSHOT.jar started by ashoksekar in /Users/ashoksekar/workspace/genAi/seek)
    //2026-03-25T08:49:10.281-05:00 DEBUG 53969 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Running with Spring Boot v3.2.5, Spring v6.1.6
    //2026-03-25T08:49:10.281-05:00  INFO 53969 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : No active profile set, falling back to 1 default profile: "default"
    //2026-03-25T08:49:11.375-05:00  INFO 53969 --- [genQry] [           main] c.nlp.rag.seek.config.JwtTokenProvider   : JwtTokenProvider initialised — expiration=86400000ms
    //2026-03-25T08:49:11.396-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.config.JwtAuthenticationFilter   : Filter 'jwtAuthenticationFilter' configured for use
    //2026-03-25T08:49:11.490-05:00  INFO 53969 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → PRIMARY (genQry) set to read-only mode
    //2026-03-25T08:49:11.492-05:00  INFO 53969 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → PRIMARY (genQry) reachable
    //2026-03-25T08:49:11.832-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.service.SQLExplanationService    : Explanation rules loaded from BusinessRulesForPrompts.json — 321 chars
    //2026-03-25T08:49:11.844-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    : Abbreviations loaded from supportingFiles/abbreviations.json — 152 entries across all sections
    //2026-03-25T08:49:11.844-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'employee' → 8 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'customer' → 5 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'order' → 5 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'product' → 5 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'department' → 5 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'invoice' → 4 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'payment' → 5 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'salary' → 5 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'address' → 4 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'account' → 4 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'vendor' → 4 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'category' → 4 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'transaction' → 4 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'revenue' → 4 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'headcount' → 4 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'recent orders' → 4 synonym(s)
    //2026-03-25T08:49:11.845-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'inventory' → 4 synonym(s)
    //2026-03-25T08:49:11.845-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    : Semantic alias groups loaded from supportingFiles/semantic.json — 17 groups
    //2026-03-25T08:49:11.850-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.MetadataDirectoryResolver      : MetadataDirectoryResolver ready — default dir: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/metadata, supportingFiles: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles
    //2026-03-25T08:49:11.853-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : ═══ RAG pipeline startup ═══
    //2026-03-25T08:49:11.853-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Root schema exists at '/Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/root/root_ecommerce_DBSchema.json' — initialising RAG from file…
    //2026-03-25T08:49:11.853-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.MetadataDirectoryResolver      : Latest schema filename registered — database='ecommerce' → file='root_ecommerce_DBSchema.json'
    //2026-03-25T08:49:11.853-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.service.SchemaExtractionService  : Building activeSchema from JSON file for database 'ecommerce'
    //2026-03-25T08:49:11.853-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.service.SchemaFileReaderService  : Using root ecommerce schema file: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/root/root_ecommerce_DBSchema.json
    //2026-03-25T08:49:11.869-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.service.SchemaFileReaderService  : Loaded schema file: root_ecommerce_DBSchema.json
    //2026-03-25T08:49:11.870-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.service.SchemaExtractionService  : buildActiveSchemaFromJson: 'ecommerce' — 9 table(s) loaded from JSON (business_context preserved for all tables)
    //2026-03-25T08:49:11.870-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'attributes.name' → 'name'
    //2026-03-25T08:49:11.872-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'attributes.unit' → 'unit'
    //2026-03-25T08:49:11.872-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'categories.name' → 'name'
    //2026-03-25T08:49:11.872-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'customers.name' → 'name'
    //2026-03-25T08:49:11.873-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'products.name' → 'name'
    //2026-03-25T08:49:11.874-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'products.sku' → 'sku'
    //2026-03-25T08:49:11.874-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'suppliers.name' → 'name'
    //2026-03-25T08:49:11.875-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  : Schema enrichment complete — 0 tables enriched, 7 columns enriched (strategy=AUTO)
    //2026-03-25T08:49:11.876-05:00  INFO 53969 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : Table 'attributes' identified as EAV (signals=6) — entityId='attributeid', attrName='name', attrValue='datatype'
    //2026-03-25T08:49:11.876-05:00  INFO 53969 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : Table 'productattributevalues' identified as EAV (signals=4) — entityId='attributeid', attrName='attribute_name', attrValue='value'
    //2026-03-25T08:49:11.877-05:00  INFO 53969 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : EAV detection complete — 2 EAV/vertical table(s) identified in schema 'ecommerce'
    //2026-03-25T08:49:11.879-05:00 DEBUG 53969 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : EAV table 'attributes' has no known_attributes catalogued — only TABLE/COLUMN chunks produced
    //2026-03-25T08:49:11.880-05:00 DEBUG 53969 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : EAV table 'productattributevalues' has no known_attributes catalogued — only TABLE/COLUMN chunks produced
    //2026-03-25T08:49:11.881-05:00  INFO 53969 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : Chunking complete: 76 metadata-enriched chunks from schema 'ecommerce' (chunkSize=80, stride=40, overlap=40)
    //2026-03-25T08:49:11.881-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Ecommerce schema loaded from file: 9 tables, 76 chunks
    //2026-03-25T08:49:11.881-05:00  INFO 53969 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Indexing 76 metadata-enriched chunks for database 'ecommerce'...
    //2026-03-25T08:49:28.912-05:00  INFO 53969 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Indexed 76 chunks (76 with embeddings, 0 keyword-only) into vector store
    //2026-03-25T08:49:28.913-05:00  INFO 53969 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Semantic search ENABLED — embedding model active
    //2026-03-25T08:49:28.981-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.VectorIndexPersistenceService  : Vector index persisted → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/vector_index_ecommerce.json (76 chunks)
    //2026-03-25T08:49:28.982-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Vector store ready — 76 entries indexed (SEMANTIC mode) user='root'
    //2026-03-25T08:49:28.982-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : ═══ RAG pipeline ready (ecommerce from file) ═══
    //2026-03-25T08:49:28.999-05:00  INFO 53969 --- [genQry] [           main] com.nlp.rag.seek.config.RedisConfig      : Redis connection factory configured → localhost:6379
    //2026-03-25T08:49:29.056-05:00  INFO 53969 --- [genQry] [           main] com.nlp.rag.seek.config.RedisConfig      : RedisTemplate<String, CacheEntry> configured with Jackson JSON serializer
    //2026-03-25T08:49:29.064-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.service.FernetEncryptionService  : Fernet: loaded key from configuration
    //2026-03-25T08:49:29.066-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.SQLGenerationService_BKP0325   : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=732 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T08:49:29.461-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.doc.service.DocumentRagService   : Doc business rules loaded from supportingFiles/BusinessRulesForPrompts.json — 571 chars
    //2026-03-25T08:49:29.496-05:00  INFO 53969 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → SECONDARY (ecommerce) reachable
    //2026-03-25T08:49:29.515-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.SQLGenerationService_BKP0325   : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=732 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T08:49:29.543-05:00  WARN 53969 --- [genQry] [           main] .s.s.UserDetailsServiceAutoConfiguration :
    //
    //Using generated security password: a0034d50-dbab-469d-94af-ee545dd0dade
    //
    //This generated password is for development use only. Your security configuration must be updated before running your application in production.
    //
    //2026-03-25T08:49:29.909-05:00  INFO 53969 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Started SeekApplication in 19.859 seconds (process running for 20.144)
    //2026-03-25T08:49:29.911-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : Running genQry DB migrations…
    //2026-03-25T08:49:29.970-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : Migration step (skipped): StatementCallback; bad SQL grammar [CREATE INDEX IF NOT EXISTS idx_transcript_session  ON transcript_details(session_id)]
    //2026-03-25T08:49:29.973-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : business_rules table already seeded (35 rows) — skipping
    //2026-03-25T08:49:29.973-05:00 DEBUG 53969 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : DATA_NOT_AVAILABLE rule already exists — skipping
    //2026-03-25T08:49:29.973-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : genQry DB migrations complete ✅
    //2026-03-25T08:49:29.978-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : BusinessRulesForPrompts.json exported — 35 rules → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/BusinessRulesForPrompts.json
    //2026-03-25T08:49:29.978-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.s.SQLGenerationService_BKP0325   : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=732 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T08:49:29.978-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : SQLGenerationService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T08:49:29.978-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.service.SQLExplanationService    : Explanation rules loaded from BusinessRulesForPrompts.json — 321 chars
    //2026-03-25T08:49:29.978-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : SQLExplanationService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T08:49:29.979-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.doc.service.DocumentRagService   : Doc business rules loaded from supportingFiles/BusinessRulesForPrompts.json — 571 chars
    //2026-03-25T08:49:29.979-05:00  INFO 53969 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : DocumentRagService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T08:49:56.613-05:00  INFO 53969 --- [genQry] [nio-9095-exec-1] c.n.rag.seek.controller.AuthController   : POST /api/v1/auth/token — username='AshokSekar'
    //2026-03-25T08:49:56.720-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-1] c.nlp.rag.seek.config.JwtTokenProvider   : JWT generated for email='mailashoky@gmail.com' username='AshokSekar' userType='Root' expires=Thu Mar 26 08:49:56 CDT 2026
    //2026-03-25T08:49:56.727-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-2] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:49:56.727-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-3] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:49:56.738-05:00  INFO 53969 --- [genQry] [nio-9095-exec-2] c.n.r.s.controller.WorkspaceController   : GET /api/v1/workSpace — username='AshokSekar' usertype='Root'
    //2026-03-25T08:49:56.738-05:00  INFO 53969 --- [genQry] [nio-9095-exec-3] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T08:49:56.742-05:00  INFO 53969 --- [genQry] [nio-9095-exec-2] c.n.r.s.controller.WorkspaceController   : Workspace: user='AshokSekar' usertype='Root' isGuest=false → fileList=[{type=database, name=ecommerce}, {type=document, name=2021.pdf}, {type=document, name=2023.pdf}, {type=document, name=AshokkumarSeka.docx}, {type=document, name=AshokW2.pdf}]
    //2026-03-25T08:49:56.753-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-3] c.n.r.s.c.QueryHistoryController         : Loaded 58 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T08:49:59.858-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-4] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:49:59.862-05:00  INFO 53969 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg in decimal
    //2026-03-25T08:49:59.862-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T08:49:59.862-05:00  INFO 53969 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T08:49:59.862-05:00  INFO 53969 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T08:49:59.862-05:00  INFO 53969 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg in decimal' | db='ecommerce' | topK=3
    //2026-03-25T08:49:59.863-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T08:49:59.863-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:49:59.967-05:00  INFO 53969 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SemanticCacheService  : ✅ Tier-1 HIT [norm-key] key='genqry:cache:ecommerce:norm:ef3d36489796e229' canonical='get 1 decimal heavier kg product than' hits=1
    //2026-03-25T08:49:59.968-05:00  INFO 53969 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ Cache HIT — returning cached response (similarity=1.0000, hits=1)
    //2026-03-25T08:49:59.968-05:00  INFO 53969 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=true | executed=false
    //2026-03-25T08:49:59.979-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:49:59.980-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T08:49:59.982-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.c.QueryHistoryController         : Loaded 58 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T08:50:03.326-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-7] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:50:03.326-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-6] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:50:03.351-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:50:07.290-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:50:07.294-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.rag.seek.service.CacheAdminService   : 🗑  Bulk evict: 2 entries (ALL) deleted
    //2026-03-25T08:50:07.295-05:00  WARN 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.controller.CacheAdminController  : ⚠️  FULL CACHE FLUSH — 2 entries deleted
    //2026-03-25T08:50:07.305-05:00 DEBUG 53969 --- [genQry] [io-9095-exec-10] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:50:07.305-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-1] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:50:07.319-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-2] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:50:08.929-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-3] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:50:08.930-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-4] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:50:08.930-05:00  INFO 53969 --- [genQry] [nio-9095-exec-3] c.n.r.s.controller.WorkspaceController   : GET /api/v1/workSpace — username='AshokSekar' usertype='Root'
    //2026-03-25T08:50:08.931-05:00  INFO 53969 --- [genQry] [nio-9095-exec-4] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T08:50:08.931-05:00  INFO 53969 --- [genQry] [nio-9095-exec-3] c.n.r.s.controller.WorkspaceController   : Workspace: user='AshokSekar' usertype='Root' isGuest=false → fileList=[{type=database, name=ecommerce}, {type=document, name=2021.pdf}, {type=document, name=2023.pdf}, {type=document, name=AshokkumarSeka.docx}, {type=document, name=AshokW2.pdf}]
    //2026-03-25T08:50:08.933-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-4] c.n.r.s.c.QueryHistoryController         : Loaded 58 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T08:50:11.846-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:50:11.848-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg in decimal
    //2026-03-25T08:50:11.848-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T08:50:11.848-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T08:50:11.848-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T08:50:11.848-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg in decimal' | db='ecommerce' | topK=3
    //2026-03-25T08:50:11.849-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T08:50:11.849-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:50:11.851-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:50:11.852-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SemanticCacheService  : Tier-1 MISS key='genqry:cache:ecommerce:norm:ef3d36489796e229'  canonical='get 1 decimal heavier kg product than'
    //2026-03-25T08:50:12.279-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SemanticCacheService  : Tier-2 MISS — no vector entries for db='ecommerce'
    //2026-03-25T08:50:12.478-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.VectorStoreService  : Semantic search 'List all the products which is heavier than 1 kg in decimal' → top 3 results:
    //2026-03-25T08:50:12.479-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.3142453175381462 | table=orderdetails#0 col=orderdetails
    //2026-03-25T08:50:12.479-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.29579759096694613 | table=products#0 col=products
    //2026-03-25T08:50:12.479-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.28472708199602886 | table=productsuppliers.productid#0 col=productsuppliers
    //2026-03-25T08:50:12.480-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'orders' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:50:12.480-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'categories' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:50:12.480-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'suppliers' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:50:12.482-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : EAV-injection: force-injected EAV table 'attributes' for query terms [weight]
    //2026-03-25T08:50:12.482-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : EAV-injection: force-injected EAV table 'productattributevalues' for query terms [weight]
    //2026-03-25T08:50:12.483-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : TopK cap applied: 8 chunks → 3 (topK=3)
    //2026-03-25T08:50:12.483-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping attributes#0 [score=0.500] for table 'attributes'
    //2026-03-25T08:50:12.483-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping productattributevalues#0 [score=0.500] for table 'productattributevalues'
    //2026-03-25T08:50:12.483-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping orderdetails#0 [score=0.314] for table 'orderdetails'
    //2026-03-25T08:50:12.483-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : Sending sanitized query to LLM with top-3 schema chunks
    //2026-03-25T08:50:12.486-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : ══════════════ PROMPT SENT TO LLM ══════════════
    //You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query..
    //
    //Rules:
    //1. Use ONLY the tables and columns that appear in the schema context below.
    //2. Write standard SQL compatible with PostgreSQL.
    //3. Use explicit JOINs with ON clauses — never implicit comma joins.
    //4. Add a WHERE clause whenever a filter is implied.
    //5. Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) when appropriate.
    //6. Add ORDER BY when a ranking or 'top N' is implied.
    //7. Return ONLY the SQL statement — no prose, no markdown fences.
    //8. CRITICAL JOIN RULE — if two tables doesnt have foreign key relationship then dont join them
    //10. MPORTANT: ALL tables and columns listed in the schema context ARE valid and exist in the database — you MUST use them if they match the user's question
    //11. End the sql statement with a semicolon.
    //
    //Follow the below rules for EAV tables:
    //1. NEVER use attribute names (e.g. Weight, Color, Size) as SQL column names.
    //2. Use WHERE <attr_name_col> = '<key>' to select a specific attribute.
    //3. Use CAST(<attr_value_col> AS <type>) when reading numeric or date values.
    //4. For multiple attributes in one row, use conditional aggregation: MAX(CASE WHEN <attr_name_col> = '<key1>' THEN CAST(<attr_value_col> AS <type>) END) AS <key1>
    //5. Join to the entity table via the entity_id column shown in the context.
    //6. EAV attributes retrieved for this query are listed in the schema context — use their FILTER and CAST HINT values exactly.
    //7. Attribute names are stored as ROW VALUES in the attribute name column.
    //8. Join the entity table to the EAV data table on the entity ID column.
    //9. Join the EAV data table to the attributes table on the attribute ID column.
    //
    //Database: ecommerce
    //
    //IMPORTANT: ALL tables listed below are valid and exist in the database. You MUST use any table or column from the schema context if it is relevant to the question. Only respond with DATA_NOT_AVAILABLE if the user's question refers to a concept that has NO matching table or column ANYWHERE in the schema context below.
    //
    //--- RETRIEVED SCHEMA CHUNKS ---
    //• attributes#0
    //  TABLE         : attributes
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : name
    //  ATTR VALUE COL: datatype
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.
    //  TEXT          : Table: attributes. Description: EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE name = '<attribute_key>' to filter, and read datatype for the value. Entity linked via attributeid. Columns: attributeid (serial) [PK]; name (character varying); datatype (character varying); unit (character varying); description (text); createdat (timestamp); Column descriptions: attributeid:
    //
    //• productattributevalues#0
    //  TABLE         : productattributevalues
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : attribute_name
    //  ATTR VALUE COL: value
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE attribute_name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.
    //  TEXT          : Table: productattributevalues. Description: EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE attribute_name = '<attribute_key>' to filter, and read value for the value. Entity linked via attributeid
    //
    //• orderdetails#0
    //  TABLE   : orderdetails
    //  COLUMNS : orderdetailid:serial, orderid:integer, productid:integer, quantity:integer, unitprice:numeric, linetotal:numeric
    //  PKs     : orderdetailid
    //  FKs     : orderid->orders.orderid, productid->products.productid
    //  CONTEXT : Line items for each order. Each row represents one product in an order.
    //  DESC    : Line items for each order. Each row represents one product in an order.
    //  TEXT    : Table: orderdetails. Description: Line items for each order. Each row represents one product in an order.. Columns: orderdetailid (serial) [PK]; orderid (integer) [FK -> orders.orderid]; productid (integer) [FK -> products.productid]; quantity (integer); unitprice (numeric); linetotal (numeric); Column descriptions: orderdetailid: orderdetailid (serial). orderid: orderid (integer). productid: productid (integer). quantity: quantity (integer). unitprice: unitprice (numeric). linetotal: linetotal (numeric).
    //--- END OF SCHEMA CHUNKS ---
    //
    //Natural-language question: List all the products which is heavier than 1 kg in decimal
    //
    //SQL:
    //
    //══════════════ END OF PROMPT ══════════════
    //2026-03-25T08:50:13.155-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : ══════════════ LLM RESPONSE ══════════════
    //DATA_NOT_AVAILABLE
    //══════════════ END OF LLM RESPONSE ══════════════
    //2026-03-25T08:50:13.155-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ LLM generated SQL:
    //══════════════════════════════
    //DATA_NOT_AVAILABLE
    //══════════════════════════════
    //2026-03-25T08:50:13.156-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLValidationService  : SQL validation: valid=false, errors=[SQL does not start with a recognised keyword (SELECT/INSERT/UPDATE/DELETE/WITH).], warnings=[SQL does not end with a semicolon.]
    //2026-03-25T08:50:13.159-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.ConfidenceScoreService   : Confidence: semantic=0.438(×0.40) | coverage=0.667(×0.20) | validity=0.0(×0.20) | tableMatch=0.000(×0.20) | → total=0.309 [LOW]
    //2026-03-25T08:50:13.159-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION PROMPT SENT TO LLM ══════════════
    //1. You are a helpful data analyst. Given the SQL query below, write a concise,
    //plain-English explanation (2–4 sentences) describing:
    //2. Which data is being retrieved or modified.
    //3. What filters or conditions apply.
    //4. How the results are ordered or grouped (if any).
    //5. The business purpose this query is likely serving.
    //
    //Do NOT include the SQL in your answer. Use simple language a non-technical
    //business user would understand.
    //
    //SQL:
    //DATA_NOT_AVAILABLE
    //══════════════ END OF EXPLANATION PROMPT ══════════════
    //2026-03-25T08:50:14.158-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION LLM RESPONSE ══════════════
    //I'm sorry, but I can't provide an explanation without the SQL query. If you can provide the query, I'd be happy to help explain it in simple terms.
    //══════════════ END OF EXPLANATION LLM RESPONSE ══════════════
    //2026-03-25T08:50:14.158-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ NL→SQL pipeline done | valid=false | confidence=0.309 [LOW] | piiTokens=0
    //2026-03-25T08:50:14.164-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.TranscriptService   : Transcript appended → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts_03_25_26.json (4 entries total)
    //2026-03-25T08:50:14.166-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.TranscriptService   : request userName='AshokSekar' resolved to userId=1000
    //2026-03-25T08:50:14.182-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.repository.UserRepository   : Transcript inserted | id=1089 | userId=1000 | status=FAILURE | dataSource=ecommerce
    //2026-03-25T08:50:14.182-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.TranscriptService   : Transcript persisted to DB with id=1089
    //2026-03-25T08:50:14.197-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] com.nlp.rag.seek.service.UserDbService   : transcript_json JDBC type=org.postgresql.util.PGobject value-preview='{"remarks": "", "userPrompt": "List all the products which is heavier than 1 kg in decimal", "explanation": "I'm sorry, '
    //2026-03-25T08:50:14.205-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] com.nlp.rag.seek.service.UserDbService   : Exported 59 transcripts → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json for user='AshokSekar'
    //2026-03-25T08:50:14.205-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:50:14.415-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T08:50:14.418-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-1/norm] key='genqry:cache:ecommerce:norm:ef3d36489796e229' canonical='get 1 decimal heavier kg product than' query='List all the products which is heavier than 1 kg in decimal'
    //2026-03-25T08:50:14.420-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-2/vec] key='genqry:cache:ecommerce:vec:2fe79b30-528b-4d3e-89ed-a88eb0ba0a25' query='List all the products which is heavier than 1 kg in decimal'
    //2026-03-25T08:50:14.421-05:00  INFO 53969 --- [genQry] [nio-9095-exec-5] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=false | executed=false
    //2026-03-25T08:50:14.435-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-7] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:50:14.436-05:00  INFO 53969 --- [genQry] [nio-9095-exec-7] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T08:50:14.438-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-7] c.n.r.s.c.QueryHistoryController         : Loaded 59 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T08:51:19.739-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:19.747-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg
    //2026-03-25T08:51:19.747-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T08:51:19.747-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T08:51:19.747-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T08:51:19.748-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg' | db='ecommerce' | topK=3
    //2026-03-25T08:51:19.748-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T08:51:19.749-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T08:51:19.754-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T08:51:19.755-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SemanticCacheService  : Tier-1 MISS key='genqry:cache:ecommerce:norm:5ac20691dbc25d94'  canonical='get 1 heavier kg product than'
    //2026-03-25T08:51:19.940-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SemanticCacheService  : Tier-2 MISS [bestScore=0.8992 < threshold=0.97] scanned=1 vec keys
    //2026-03-25T08:51:20.136-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.VectorStoreService  : Semantic search 'List all the products which is heavier than 1 kg' → top 3 results:
    //2026-03-25T08:51:20.137-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.29934204851486534 | table=orderdetails#0 col=orderdetails
    //2026-03-25T08:51:20.137-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2968691576273396 | table=products#0 col=products
    //2026-03-25T08:51:20.137-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.24062708087498832 | table=productsuppliers.productid#0 col=productsuppliers
    //2026-03-25T08:51:20.137-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'orders' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:51:20.137-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'categories' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:51:20.137-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'suppliers' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:51:20.137-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : EAV-injection: force-injected EAV table 'attributes' for query terms [weight]
    //2026-03-25T08:51:20.138-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : EAV-injection: force-injected EAV table 'productattributevalues' for query terms [weight]
    //2026-03-25T08:51:20.138-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : TopK cap applied: 8 chunks → 3 (topK=3)
    //2026-03-25T08:51:20.138-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping attributes#0 [score=0.500] for table 'attributes'
    //2026-03-25T08:51:20.138-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping productattributevalues#0 [score=0.500] for table 'productattributevalues'
    //2026-03-25T08:51:20.138-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping orderdetails#0 [score=0.299] for table 'orderdetails'
    //2026-03-25T08:51:20.138-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : Sending sanitized query to LLM with top-3 schema chunks
    //2026-03-25T08:51:20.138-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : ══════════════ PROMPT SENT TO LLM ══════════════
    //You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query..
    //
    //Rules:
    //1. Use ONLY the tables and columns that appear in the schema context below.
    //2. Write standard SQL compatible with PostgreSQL.
    //3. Use explicit JOINs with ON clauses — never implicit comma joins.
    //4. Add a WHERE clause whenever a filter is implied.
    //5. Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) when appropriate.
    //6. Add ORDER BY when a ranking or 'top N' is implied.
    //7. Return ONLY the SQL statement — no prose, no markdown fences.
    //8. CRITICAL JOIN RULE — if two tables doesnt have foreign key relationship then dont join them
    //10. MPORTANT: ALL tables and columns listed in the schema context ARE valid and exist in the database — you MUST use them if they match the user's question
    //11. End the sql statement with a semicolon.
    //
    //Follow the below rules for EAV tables:
    //1. NEVER use attribute names (e.g. Weight, Color, Size) as SQL column names.
    //2. Use WHERE <attr_name_col> = '<key>' to select a specific attribute.
    //3. Use CAST(<attr_value_col> AS <type>) when reading numeric or date values.
    //4. For multiple attributes in one row, use conditional aggregation: MAX(CASE WHEN <attr_name_col> = '<key1>' THEN CAST(<attr_value_col> AS <type>) END) AS <key1>
    //5. Join to the entity table via the entity_id column shown in the context.
    //6. EAV attributes retrieved for this query are listed in the schema context — use their FILTER and CAST HINT values exactly.
    //7. Attribute names are stored as ROW VALUES in the attribute name column.
    //8. Join the entity table to the EAV data table on the entity ID column.
    //9. Join the EAV data table to the attributes table on the attribute ID column.
    //
    //Database: ecommerce
    //
    //IMPORTANT: ALL tables listed below are valid and exist in the database. You MUST use any table or column from the schema context if it is relevant to the question. Only respond with DATA_NOT_AVAILABLE if the user's question refers to a concept that has NO matching table or column ANYWHERE in the schema context below.
    //
    //--- RETRIEVED SCHEMA CHUNKS ---
    //• attributes#0
    //  TABLE         : attributes
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : name
    //  ATTR VALUE COL: datatype
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.
    //  TEXT          : Table: attributes. Description: EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE name = '<attribute_key>' to filter, and read datatype for the value. Entity linked via attributeid. Columns: attributeid (serial) [PK]; name (character varying); datatype (character varying); unit (character varying); description (text); createdat (timestamp); Column descriptions: attributeid:
    //
    //• productattributevalues#0
    //  TABLE         : productattributevalues
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : attribute_name
    //  ATTR VALUE COL: value
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE attribute_name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.
    //  TEXT          : Table: productattributevalues. Description: EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE attribute_name = '<attribute_key>' to filter, and read value for the value. Entity linked via attributeid
    //
    //• orderdetails#0
    //  TABLE   : orderdetails
    //  COLUMNS : orderdetailid:serial, orderid:integer, productid:integer, quantity:integer, unitprice:numeric, linetotal:numeric
    //  PKs     : orderdetailid
    //  FKs     : orderid->orders.orderid, productid->products.productid
    //  CONTEXT : Line items for each order. Each row represents one product in an order.
    //  DESC    : Line items for each order. Each row represents one product in an order.
    //  TEXT    : Table: orderdetails. Description: Line items for each order. Each row represents one product in an order.. Columns: orderdetailid (serial) [PK]; orderid (integer) [FK -> orders.orderid]; productid (integer) [FK -> products.productid]; quantity (integer); unitprice (numeric); linetotal (numeric); Column descriptions: orderdetailid: orderdetailid (serial). orderid: orderid (integer). productid: productid (integer). quantity: quantity (integer). unitprice: unitprice (numeric). linetotal: linetotal (numeric).
    //--- END OF SCHEMA CHUNKS ---
    //
    //Natural-language question: List all the products which is heavier than 1 kg
    //
    //SQL:
    //
    //══════════════ END OF PROMPT ══════════════
    //2026-03-25T08:51:20.546-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : ══════════════ LLM RESPONSE ══════════════
    //DATA_NOT_AVAILABLE
    //══════════════ END OF LLM RESPONSE ══════════════
    //2026-03-25T08:51:20.546-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ LLM generated SQL:
    //══════════════════════════════
    //DATA_NOT_AVAILABLE
    //══════════════════════════════
    //2026-03-25T08:51:20.546-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLValidationService  : SQL validation: valid=false, errors=[SQL does not start with a recognised keyword (SELECT/INSERT/UPDATE/DELETE/WITH).], warnings=[SQL does not end with a semicolon.]
    //2026-03-25T08:51:20.547-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.ConfidenceScoreService   : Confidence: semantic=0.433(×0.40) | coverage=0.667(×0.20) | validity=0.0(×0.20) | tableMatch=0.000(×0.20) | → total=0.307 [LOW]
    //2026-03-25T08:51:20.547-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION PROMPT SENT TO LLM ══════════════
    //1. You are a helpful data analyst. Given the SQL query below, write a concise,
    //plain-English explanation (2–4 sentences) describing:
    //2. Which data is being retrieved or modified.
    //3. What filters or conditions apply.
    //4. How the results are ordered or grouped (if any).
    //5. The business purpose this query is likely serving.
    //
    //Do NOT include the SQL in your answer. Use simple language a non-technical
    //business user would understand.
    //
    //SQL:
    //DATA_NOT_AVAILABLE
    //══════════════ END OF EXPLANATION PROMPT ══════════════
    //2026-03-25T08:51:21.762-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION LLM RESPONSE ══════════════
    //I'm sorry, but I can't provide an explanation without the SQL query. If you can provide the query, I'd be happy to help explain it in simple terms.
    //══════════════ END OF EXPLANATION LLM RESPONSE ══════════════
    //2026-03-25T08:51:21.762-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ NL→SQL pipeline done | valid=false | confidence=0.307 [LOW] | piiTokens=0
    //2026-03-25T08:51:21.765-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.TranscriptService   : Transcript appended → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts_03_25_26.json (5 entries total)
    //2026-03-25T08:51:21.768-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.TranscriptService   : request userName='AshokSekar' resolved to userId=1000
    //2026-03-25T08:51:21.772-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.repository.UserRepository   : Transcript inserted | id=1090 | userId=1000 | status=FAILURE | dataSource=ecommerce
    //2026-03-25T08:51:21.772-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.TranscriptService   : Transcript persisted to DB with id=1090
    //2026-03-25T08:51:21.775-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] com.nlp.rag.seek.service.UserDbService   : transcript_json JDBC type=org.postgresql.util.PGobject value-preview='{"remarks": "", "userPrompt": "List all the products which is heavier than 1 kg", "explanation": "I'm sorry, but I can't'
    //2026-03-25T08:51:21.781-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] com.nlp.rag.seek.service.UserDbService   : Exported 60 transcripts → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json for user='AshokSekar'
    //2026-03-25T08:51:21.781-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T08:51:21.951-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T08:51:21.954-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-1/norm] key='genqry:cache:ecommerce:norm:5ac20691dbc25d94' canonical='get 1 heavier kg product than' query='List all the products which is heavier than 1 kg'
    //2026-03-25T08:51:21.958-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-2/vec] key='genqry:cache:ecommerce:vec:7de0dc7b-90b3-4ffe-9945-0082326c3d55' query='List all the products which is heavier than 1 kg'
    //2026-03-25T08:51:21.959-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=false | executed=false
    //2026-03-25T08:51:21.968-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:21.969-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T08:51:21.970-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.c.QueryHistoryController         : Loaded 60 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T08:51:29.136-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-1] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:29.136-05:00 DEBUG 53969 --- [genQry] [io-9095-exec-10] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:29.174-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-2] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:31.270-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-3] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:31.275-05:00  INFO 53969 --- [genQry] [nio-9095-exec-3] c.n.rag.seek.service.CacheAdminService   : 🗑  Bulk evict: 4 entries (ALL) deleted
    //2026-03-25T08:51:31.275-05:00  WARN 53969 --- [genQry] [nio-9095-exec-3] c.n.r.s.controller.CacheAdminController  : ⚠️  FULL CACHE FLUSH — 4 entries deleted
    //2026-03-25T08:51:31.287-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-4] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:31.287-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-5] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:31.300-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-7] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:32.646-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:32.646-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-6] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:32.646-05:00  INFO 53969 --- [genQry] [nio-9095-exec-6] c.n.r.s.controller.WorkspaceController   : GET /api/v1/workSpace — username='AshokSekar' usertype='Root'
    //2026-03-25T08:51:32.646-05:00  INFO 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T08:51:32.647-05:00  INFO 53969 --- [genQry] [nio-9095-exec-6] c.n.r.s.controller.WorkspaceController   : Workspace: user='AshokSekar' usertype='Root' isGuest=false → fileList=[{type=database, name=ecommerce}, {type=document, name=2021.pdf}, {type=document, name=2023.pdf}, {type=document, name=AshokkumarSeka.docx}, {type=document, name=AshokW2.pdf}]
    //2026-03-25T08:51:32.647-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-8] c.n.r.s.c.QueryHistoryController         : Loaded 60 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T08:51:41.810-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:41.811-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg
    //2026-03-25T08:51:41.812-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T08:51:41.812-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T08:51:41.812-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T08:51:41.812-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg' | db='ecommerce' | topK=3
    //2026-03-25T08:51:41.812-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T08:51:41.812-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T08:51:41.813-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T08:51:41.813-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.seek.service.SemanticCacheService  : Tier-1 MISS key='genqry:cache:ecommerce:norm:5ac20691dbc25d94'  canonical='get 1 heavier kg product than'
    //2026-03-25T08:51:42.026-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.seek.service.SemanticCacheService  : Tier-2 MISS — no vector entries for db='ecommerce'
    //2026-03-25T08:51:42.225-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.rag.seek.service.VectorStoreService  : Semantic search 'List all the products which is heavier than 1 kg' → top 3 results:
    //2026-03-25T08:51:42.225-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2992930265002506 | table=orderdetails#0 col=orderdetails
    //2026-03-25T08:51:42.225-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2968647376192156 | table=products#0 col=products
    //2026-03-25T08:51:42.225-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2405617458251739 | table=productsuppliers.productid#0 col=productsuppliers
    //2026-03-25T08:51:42.225-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'orders' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:51:42.225-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'categories' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:51:42.225-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'suppliers' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T08:51:42.226-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : EAV-injection: force-injected EAV table 'attributes' for query terms [weight]
    //2026-03-25T08:51:42.226-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : EAV-injection: force-injected EAV table 'productattributevalues' for query terms [weight]
    //2026-03-25T08:51:42.226-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : TopK cap applied: 8 chunks → 3 (topK=3)
    //2026-03-25T08:51:42.226-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping attributes#0 [score=0.500] for table 'attributes'
    //2026-03-25T08:51:42.226-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping productattributevalues#0 [score=0.500] for table 'productattributevalues'
    //2026-03-25T08:51:42.226-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping orderdetails#0 [score=0.299] for table 'orderdetails'
    //2026-03-25T08:51:42.226-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : Sending sanitized query to LLM with top-3 schema chunks
    //2026-03-25T08:51:42.226-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : ══════════════ PROMPT SENT TO LLM ══════════════
    //You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query..
    //
    //Rules:
    //1. Use ONLY the tables and columns that appear in the schema context below.
    //2. Write standard SQL compatible with PostgreSQL.
    //3. Use explicit JOINs with ON clauses — never implicit comma joins.
    //4. Add a WHERE clause whenever a filter is implied.
    //5. Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) when appropriate.
    //6. Add ORDER BY when a ranking or 'top N' is implied.
    //7. Return ONLY the SQL statement — no prose, no markdown fences.
    //8. CRITICAL JOIN RULE — if two tables doesnt have foreign key relationship then dont join them
    //10. MPORTANT: ALL tables and columns listed in the schema context ARE valid and exist in the database — you MUST use them if they match the user's question
    //11. End the sql statement with a semicolon.
    //
    //Follow the below rules for EAV tables:
    //1. NEVER use attribute names (e.g. Weight, Color, Size) as SQL column names.
    //2. Use WHERE <attr_name_col> = '<key>' to select a specific attribute.
    //3. Use CAST(<attr_value_col> AS <type>) when reading numeric or date values.
    //4. For multiple attributes in one row, use conditional aggregation: MAX(CASE WHEN <attr_name_col> = '<key1>' THEN CAST(<attr_value_col> AS <type>) END) AS <key1>
    //5. Join to the entity table via the entity_id column shown in the context.
    //6. EAV attributes retrieved for this query are listed in the schema context — use their FILTER and CAST HINT values exactly.
    //7. Attribute names are stored as ROW VALUES in the attribute name column.
    //8. Join the entity table to the EAV data table on the entity ID column.
    //9. Join the EAV data table to the attributes table on the attribute ID column.
    //
    //Database: ecommerce
    //
    //IMPORTANT: ALL tables listed below are valid and exist in the database. You MUST use any table or column from the schema context if it is relevant to the question. Only respond with DATA_NOT_AVAILABLE if the user's question refers to a concept that has NO matching table or column ANYWHERE in the schema context below.
    //
    //--- RETRIEVED SCHEMA CHUNKS ---
    //• attributes#0
    //  TABLE         : attributes
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : name
    //  ATTR VALUE COL: datatype
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.
    //  TEXT          : Table: attributes. Description: EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE name = '<attribute_key>' to filter, and read datatype for the value. Entity linked via attributeid. Columns: attributeid (serial) [PK]; name (character varying); datatype (character varying); unit (character varying); description (text); createdat (timestamp); Column descriptions: attributeid:
    //
    //• productattributevalues#0
    //  TABLE         : productattributevalues
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : attribute_name
    //  ATTR VALUE COL: value
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE attribute_name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.
    //  TEXT          : Table: productattributevalues. Description: EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE attribute_name = '<attribute_key>' to filter, and read value for the value. Entity linked via attributeid
    //
    //• orderdetails#0
    //  TABLE   : orderdetails
    //  COLUMNS : orderdetailid:serial, orderid:integer, productid:integer, quantity:integer, unitprice:numeric, linetotal:numeric
    //  PKs     : orderdetailid
    //  FKs     : orderid->orders.orderid, productid->products.productid
    //  CONTEXT : Line items for each order. Each row represents one product in an order.
    //  DESC    : Line items for each order. Each row represents one product in an order.
    //  TEXT    : Table: orderdetails. Description: Line items for each order. Each row represents one product in an order.. Columns: orderdetailid (serial) [PK]; orderid (integer) [FK -> orders.orderid]; productid (integer) [FK -> products.productid]; quantity (integer); unitprice (numeric); linetotal (numeric); Column descriptions: orderdetailid: orderdetailid (serial). orderid: orderid (integer). productid: productid (integer). quantity: quantity (integer). unitprice: unitprice (numeric). linetotal: linetotal (numeric).
    //--- END OF SCHEMA CHUNKS ---
    //
    //Natural-language question: List all the products which is heavier than 1 kg
    //
    //SQL:
    //
    //══════════════ END OF PROMPT ══════════════
    //2026-03-25T08:51:42.629-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : ══════════════ LLM RESPONSE ══════════════
    //DATA_NOT_AVAILABLE
    //══════════════ END OF LLM RESPONSE ══════════════
    //2026-03-25T08:51:42.629-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ LLM generated SQL:
    //══════════════════════════════
    //DATA_NOT_AVAILABLE
    //══════════════════════════════
    //2026-03-25T08:51:42.629-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.seek.service.SQLValidationService  : SQL validation: valid=false, errors=[SQL does not start with a recognised keyword (SELECT/INSERT/UPDATE/DELETE/WITH).], warnings=[SQL does not end with a semicolon.]
    //2026-03-25T08:51:42.629-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.service.ConfidenceScoreService   : Confidence: semantic=0.433(×0.40) | coverage=0.667(×0.20) | validity=0.0(×0.20) | tableMatch=0.000(×0.20) | → total=0.307 [LOW]
    //2026-03-25T08:51:42.629-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION PROMPT SENT TO LLM ══════════════
    //1. You are a helpful data analyst. Given the SQL query below, write a concise,
    //plain-English explanation (2–4 sentences) describing:
    //2. Which data is being retrieved or modified.
    //3. What filters or conditions apply.
    //4. How the results are ordered or grouped (if any).
    //5. The business purpose this query is likely serving.
    //
    //Do NOT include the SQL in your answer. Use simple language a non-technical
    //business user would understand.
    //
    //SQL:
    //DATA_NOT_AVAILABLE
    //══════════════ END OF EXPLANATION PROMPT ══════════════
    //2026-03-25T08:51:44.301-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION LLM RESPONSE ══════════════
    //I'm sorry, but I can't provide an explanation without the SQL query. If you can provide the query, I'd be happy to help explain it in simple terms.
    //══════════════ END OF EXPLANATION LLM RESPONSE ══════════════
    //2026-03-25T08:51:44.302-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ NL→SQL pipeline done | valid=false | confidence=0.307 [LOW] | piiTokens=0
    //2026-03-25T08:51:44.305-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.rag.seek.service.TranscriptService   : Transcript appended → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts_03_25_26.json (6 entries total)
    //2026-03-25T08:51:44.310-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.rag.seek.service.TranscriptService   : request userName='AshokSekar' resolved to userId=1000
    //2026-03-25T08:51:44.313-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.rag.seek.repository.UserRepository   : Transcript inserted | id=1091 | userId=1000 | status=FAILURE | dataSource=ecommerce
    //2026-03-25T08:51:44.313-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.rag.seek.service.TranscriptService   : Transcript persisted to DB with id=1091
    //2026-03-25T08:51:44.321-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] com.nlp.rag.seek.service.UserDbService   : transcript_json JDBC type=org.postgresql.util.PGobject value-preview='{"remarks": "", "userPrompt": "List all the products which is heavier than 1 kg", "explanation": "I'm sorry, but I can't'
    //2026-03-25T08:51:44.326-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] com.nlp.rag.seek.service.UserDbService   : Exported 61 transcripts → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json for user='AshokSekar'
    //2026-03-25T08:51:44.326-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T08:51:44.472-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-9] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T08:51:44.474-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-1/norm] key='genqry:cache:ecommerce:norm:5ac20691dbc25d94' canonical='get 1 heavier kg product than' query='List all the products which is heavier than 1 kg'
    //2026-03-25T08:51:44.476-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-2/vec] key='genqry:cache:ecommerce:vec:bdbc345d-e1c9-4ab6-894c-2f19229f0feb' query='List all the products which is heavier than 1 kg'
    //2026-03-25T08:51:44.476-05:00  INFO 53969 --- [genQry] [nio-9095-exec-9] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=false | executed=false
    //2026-03-25T08:51:44.490-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-1] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T08:51:44.491-05:00  INFO 53969 --- [genQry] [nio-9095-exec-1] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T08:51:44.492-05:00 DEBUG 53969 --- [genQry] [nio-9095-exec-1] c.n.r.s.c.QueryHistoryController         : Loaded 61 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //^C%                                                                                                                                                                                                              ashoksekar@ashoks-MacBook-Pro seek % ./mvnw clean package -DskipTests -q && java -jar target/genQry-0.0.1-SNAPSHOT.jar
    //
    //  .   ____          _            __ _ _
    // /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
    //( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
    // \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
    //  '  |____| .__|_| |_|_| |_\__, | / / / /
    // =========|_|==============|___/=/_/_/_/
    // :: Spring Boot ::                (v3.2.5)
    //
    //2026-03-25T09:20:12.359-05:00  INFO 55319 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Starting SeekApplication v0.0.1-SNAPSHOT using Java 17.0.18 with PID 55319 (/Users/ashoksekar/workspace/genAi/seek/target/genQry-0.0.1-SNAPSHOT.jar started by ashoksekar in /Users/ashoksekar/workspace/genAi/seek)
    //2026-03-25T09:20:12.360-05:00 DEBUG 55319 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Running with Spring Boot v3.2.5, Spring v6.1.6
    //2026-03-25T09:20:12.361-05:00  INFO 55319 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : No active profile set, falling back to 1 default profile: "default"
    //2026-03-25T09:20:13.657-05:00  INFO 55319 --- [genQry] [           main] c.nlp.rag.seek.config.JwtTokenProvider   : JwtTokenProvider initialised — expiration=86400000ms
    //2026-03-25T09:20:13.677-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.config.JwtAuthenticationFilter   : Filter 'jwtAuthenticationFilter' configured for use
    //2026-03-25T09:20:13.771-05:00  INFO 55319 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → PRIMARY (genQry) set to read-only mode
    //2026-03-25T09:20:13.774-05:00  INFO 55319 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → PRIMARY (genQry) reachable
    //2026-03-25T09:20:14.100-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.service.SQLExplanationService    : Explanation rules loaded from BusinessRulesForPrompts.json — 321 chars
    //2026-03-25T09:20:14.111-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    : Abbreviations loaded from supportingFiles/abbreviations.json — 152 entries across all sections
    //2026-03-25T09:20:14.112-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'employee' → 8 synonym(s)
    //2026-03-25T09:20:14.112-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'customer' → 5 synonym(s)
    //2026-03-25T09:20:14.112-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'order' → 5 synonym(s)
    //2026-03-25T09:20:14.112-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'product' → 5 synonym(s)
    //2026-03-25T09:20:14.112-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'department' → 5 synonym(s)
    //2026-03-25T09:20:14.113-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'invoice' → 4 synonym(s)
    //2026-03-25T09:20:14.113-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'payment' → 5 synonym(s)
    //2026-03-25T09:20:14.113-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'salary' → 5 synonym(s)
    //2026-03-25T09:20:14.113-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'address' → 4 synonym(s)
    //2026-03-25T09:20:14.113-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'account' → 4 synonym(s)
    //2026-03-25T09:20:14.113-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'vendor' → 4 synonym(s)
    //2026-03-25T09:20:14.113-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'category' → 4 synonym(s)
    //2026-03-25T09:20:14.113-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'transaction' → 4 synonym(s)
    //2026-03-25T09:20:14.113-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'revenue' → 4 synonym(s)
    //2026-03-25T09:20:14.113-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'headcount' → 4 synonym(s)
    //2026-03-25T09:20:14.113-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'recent orders' → 4 synonym(s)
    //2026-03-25T09:20:14.113-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'inventory' → 4 synonym(s)
    //2026-03-25T09:20:14.113-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    : Semantic alias groups loaded from supportingFiles/semantic.json — 17 groups
    //2026-03-25T09:20:14.118-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.MetadataDirectoryResolver      : MetadataDirectoryResolver ready — default dir: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/metadata, supportingFiles: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles
    //2026-03-25T09:20:14.122-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : ═══ RAG pipeline startup ═══
    //2026-03-25T09:20:14.122-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Root schema exists at '/Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/root/root_ecommerce_DBSchema.json' — initialising RAG from file…
    //2026-03-25T09:20:14.122-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.MetadataDirectoryResolver      : Latest schema filename registered — database='ecommerce' → file='root_ecommerce_DBSchema.json'
    //2026-03-25T09:20:14.122-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.service.SchemaExtractionService  : Building activeSchema from JSON file for database 'ecommerce'
    //2026-03-25T09:20:14.122-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.service.SchemaFileReaderService  : Using root ecommerce schema file: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/root/root_ecommerce_DBSchema.json
    //2026-03-25T09:20:14.137-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.service.SchemaFileReaderService  : Loaded schema file: root_ecommerce_DBSchema.json
    //2026-03-25T09:20:14.138-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.service.SchemaExtractionService  : buildActiveSchemaFromJson: 'ecommerce' — 9 table(s) loaded from JSON (business_context preserved for all tables)
    //2026-03-25T09:20:14.138-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'attributes.name' → 'name'
    //2026-03-25T09:20:14.139-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'attributes.unit' → 'unit'
    //2026-03-25T09:20:14.139-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'categories.name' → 'name'
    //2026-03-25T09:20:14.140-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'customers.name' → 'name'
    //2026-03-25T09:20:14.140-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'products.name' → 'name'
    //2026-03-25T09:20:14.141-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'products.sku' → 'sku'
    //2026-03-25T09:20:14.141-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'suppliers.name' → 'name'
    //2026-03-25T09:20:14.141-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  : Schema enrichment complete — 0 tables enriched, 7 columns enriched (strategy=AUTO)
    //2026-03-25T09:20:14.142-05:00  INFO 55319 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : Table 'attributes' identified as EAV (signals=6) — entityId='attributeid', attrName='name', attrValue='datatype'
    //2026-03-25T09:20:14.143-05:00  INFO 55319 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : Table 'productattributevalues' identified as EAV (signals=4) — entityId='attributeid', attrName='attribute_name', attrValue='value'
    //2026-03-25T09:20:14.143-05:00  INFO 55319 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : EAV detection complete — 2 EAV/vertical table(s) identified in schema 'ecommerce'
    //2026-03-25T09:20:14.145-05:00 DEBUG 55319 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : EAV table 'attributes' has no known_attributes catalogued — only TABLE/COLUMN chunks produced
    //2026-03-25T09:20:14.146-05:00 DEBUG 55319 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : EAV table 'productattributevalues' has no known_attributes catalogued — only TABLE/COLUMN chunks produced
    //2026-03-25T09:20:14.147-05:00  INFO 55319 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : Chunking complete: 76 metadata-enriched chunks from schema 'ecommerce' (chunkSize=80, stride=40, overlap=40)
    //2026-03-25T09:20:14.147-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Ecommerce schema loaded from file: 9 tables, 76 chunks
    //2026-03-25T09:20:14.147-05:00  INFO 55319 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Indexing 76 metadata-enriched chunks for database 'ecommerce'...
    //2026-03-25T09:20:31.532-05:00  INFO 55319 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Indexed 76 chunks (76 with embeddings, 0 keyword-only) into vector store
    //2026-03-25T09:20:31.534-05:00  INFO 55319 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Semantic search ENABLED — embedding model active
    //2026-03-25T09:20:31.601-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.VectorIndexPersistenceService  : Vector index persisted → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/vector_index_ecommerce.json (76 chunks)
    //2026-03-25T09:20:31.602-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Vector store ready — 76 entries indexed (SEMANTIC mode) user='root'
    //2026-03-25T09:20:31.602-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : ═══ RAG pipeline ready (ecommerce from file) ═══
    //2026-03-25T09:20:31.626-05:00  INFO 55319 --- [genQry] [           main] com.nlp.rag.seek.config.RedisConfig      : Redis connection factory configured → localhost:6379
    //2026-03-25T09:20:31.696-05:00  INFO 55319 --- [genQry] [           main] com.nlp.rag.seek.config.RedisConfig      : RedisTemplate<String, CacheEntry> configured with Jackson JSON serializer
    //2026-03-25T09:20:31.705-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.service.FernetEncryptionService  : Fernet: loaded key from configuration
    //2026-03-25T09:20:31.707-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.SQLGenerationService_BKP0325   : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=732 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T09:20:32.235-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.doc.service.DocumentRagService   : Doc business rules loaded from supportingFiles/BusinessRulesForPrompts.json — 571 chars
    //2026-03-25T09:20:32.274-05:00  INFO 55319 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → SECONDARY (ecommerce) reachable
    //2026-03-25T09:20:32.294-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.SQLGenerationService_BKP0325   : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=732 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T09:20:32.328-05:00  WARN 55319 --- [genQry] [           main] .s.s.UserDetailsServiceAutoConfiguration :
    //
    //Using generated security password: e04237ab-4703-439f-877f-f1d7aa411ad5
    //
    //This generated password is for development use only. Your security configuration must be updated before running your application in production.
    //
    //2026-03-25T09:20:32.908-05:00  INFO 55319 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Started SeekApplication in 20.794 seconds (process running for 21.109)
    //2026-03-25T09:20:32.911-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : Running genQry DB migrations…
    //2026-03-25T09:20:32.998-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : Migration step (skipped): StatementCallback; bad SQL grammar [CREATE INDEX IF NOT EXISTS idx_transcript_session  ON transcript_details(session_id)]
    //2026-03-25T09:20:33.002-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : business_rules table already seeded (35 rows) — skipping
    //2026-03-25T09:20:33.003-05:00 DEBUG 55319 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : DATA_NOT_AVAILABLE rule already exists — skipping
    //2026-03-25T09:20:33.003-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : genQry DB migrations complete ✅
    //2026-03-25T09:20:33.011-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : BusinessRulesForPrompts.json exported — 35 rules → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/BusinessRulesForPrompts.json
    //2026-03-25T09:20:33.011-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.s.SQLGenerationService_BKP0325   : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=732 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T09:20:33.011-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : SQLGenerationService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T09:20:33.012-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.service.SQLExplanationService    : Explanation rules loaded from BusinessRulesForPrompts.json — 321 chars
    //2026-03-25T09:20:33.012-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : SQLExplanationService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T09:20:33.012-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.doc.service.DocumentRagService   : Doc business rules loaded from supportingFiles/BusinessRulesForPrompts.json — 571 chars
    //2026-03-25T09:20:33.012-05:00  INFO 55319 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : DocumentRagService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T09:21:32.758-05:00  INFO 55319 --- [genQry] [nio-9095-exec-2] c.n.rag.seek.controller.AuthController   : POST /api/v1/auth/token — username='AshokSekar'
    //2026-03-25T09:21:32.849-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-2] c.nlp.rag.seek.config.JwtTokenProvider   : JWT generated for email='mailashoky@gmail.com' username='AshokSekar' userType='Root' expires=Thu Mar 26 09:21:32 CDT 2026
    //2026-03-25T09:21:32.853-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-1] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:21:32.853-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-3] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:21:32.861-05:00  INFO 55319 --- [genQry] [nio-9095-exec-1] c.n.r.s.controller.WorkspaceController   : GET /api/v1/workSpace — username='AshokSekar' usertype='Root'
    //2026-03-25T09:21:32.862-05:00  INFO 55319 --- [genQry] [nio-9095-exec-3] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T09:21:32.863-05:00  INFO 55319 --- [genQry] [nio-9095-exec-1] c.n.r.s.controller.WorkspaceController   : Workspace: user='AshokSekar' usertype='Root' isGuest=false → fileList=[{name=ecommerce, type=database}, {name=2021.pdf, type=document}, {name=2023.pdf, type=document}, {name=AshokkumarSeka.docx, type=document}, {name=AshokW2.pdf, type=document}]
    //2026-03-25T09:21:32.876-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-3] c.n.r.s.c.QueryHistoryController         : Loaded 61 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T09:21:40.316-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:21:40.321-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg in decimal
    //2026-03-25T09:21:40.322-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T09:21:40.322-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T09:21:40.322-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T09:21:40.322-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg in decimal' | db='ecommerce' | topK=3
    //2026-03-25T09:21:40.323-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T09:21:40.323-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T09:21:40.394-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T09:21:40.394-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SemanticCacheService  : Tier-1 MISS key='genqry:cache:ecommerce:norm:ef3d36489796e229'  canonical='get 1 decimal heavier kg product than'
    //2026-03-25T09:21:40.642-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SemanticCacheService  : Tier-2 MISS [bestScore=0.8992 < threshold=0.97] scanned=1 vec keys
    //2026-03-25T09:21:40.801-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.VectorStoreService  : Semantic search 'List all the products which is heavier than 1 kg in decimal' → top 3 results:
    //2026-03-25T09:21:40.801-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.3142453175381462 | table=orderdetails#0 col=orderdetails
    //2026-03-25T09:21:40.801-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.29579759096694613 | table=products#0 col=products
    //2026-03-25T09:21:40.801-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2847524457704409 | table=productsuppliers.productid#0 col=productsuppliers
    //2026-03-25T09:21:40.802-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'orders' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T09:21:40.802-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'categories' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T09:21:40.802-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'suppliers' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T09:21:40.803-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : EAV-injection: force-injected EAV table 'attributes' for query terms [weight]
    //2026-03-25T09:21:40.803-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : EAV-injection: force-injected EAV table 'productattributevalues' for query terms [weight]
    //2026-03-25T09:21:40.804-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : TopK cap applied: 8 chunks → 3 (topK=3)
    //2026-03-25T09:21:40.804-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping attributes#0 [score=0.500] for table 'attributes'
    //2026-03-25T09:21:40.804-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping productattributevalues#0 [score=0.500] for table 'productattributevalues'
    //2026-03-25T09:21:40.804-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping orderdetails#0 [score=0.314] for table 'orderdetails'
    //2026-03-25T09:21:40.804-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : Sending sanitized query to LLM with top-3 schema chunks
    //2026-03-25T09:21:40.806-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : ══════════════ PROMPT SENT TO LLM ══════════════
    //You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query..
    //
    //Rules:
    //1. Use ONLY the tables and columns that appear in the schema context below.
    //2. Write standard SQL compatible with PostgreSQL.
    //3. Use explicit JOINs with ON clauses — never implicit comma joins.
    //4. Add a WHERE clause whenever a filter is implied.
    //5. Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) when appropriate.
    //6. Add ORDER BY when a ranking or 'top N' is implied.
    //7. Return ONLY the SQL statement — no prose, no markdown fences.
    //8. CRITICAL JOIN RULE — if two tables doesnt have foreign key relationship then dont join them
    //10. MPORTANT: ALL tables and columns listed in the schema context ARE valid and exist in the database — you MUST use them if they match the user's question
    //11. End the sql statement with a semicolon.
    //
    //Follow the below rules for EAV tables:
    //1. NEVER use attribute names (e.g. Weight, Color, Size) as SQL column names.
    //2. Use WHERE <attr_name_col> = '<key>' to select a specific attribute.
    //3. Use CAST(<attr_value_col> AS <type>) when reading numeric or date values.
    //4. For multiple attributes in one row, use conditional aggregation: MAX(CASE WHEN <attr_name_col> = '<key1>' THEN CAST(<attr_value_col> AS <type>) END) AS <key1>
    //5. Join to the entity table via the entity_id column shown in the context.
    //6. EAV attributes retrieved for this query are listed in the schema context — use their FILTER and CAST HINT values exactly.
    //7. Attribute names are stored as ROW VALUES in the attribute name column.
    //8. Join the entity table to the EAV data table on the entity ID column.
    //9. Join the EAV data table to the attributes table on the attribute ID column.
    //
    //Database: ecommerce
    //
    //IMPORTANT: ALL tables listed below are valid and exist in the database. You MUST use any table or column from the schema context if it is relevant to the question. Only respond with DATA_NOT_AVAILABLE if the user's question refers to a concept that has NO matching table or column ANYWHERE in the schema context below.
    //
    //--- RETRIEVED SCHEMA CHUNKS ---
    //• attributes#0
    //  TABLE         : attributes
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : name
    //  ATTR VALUE COL: datatype
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.
    //  TEXT          : Table: attributes. Description: EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE name = '<attribute_key>' to filter, and read datatype for the value. Entity linked via attributeid. Columns: attributeid (serial) [PK]; name (character varying); datatype (character varying); unit (character varying); description (text); createdat (timestamp); Column descriptions: attributeid:
    //
    //• productattributevalues#0
    //  TABLE         : productattributevalues
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : attribute_name
    //  ATTR VALUE COL: value
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE attribute_name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.
    //  TEXT          : Table: productattributevalues. Description: EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE attribute_name = '<attribute_key>' to filter, and read value for the value. Entity linked via attributeid
    //
    //• orderdetails#0
    //  TABLE   : orderdetails
    //  COLUMNS : orderdetailid:serial, orderid:integer, productid:integer, quantity:integer, unitprice:numeric, linetotal:numeric
    //  PKs     : orderdetailid
    //  FKs     : orderid->orders.orderid, productid->products.productid
    //  CONTEXT : Line items for each order. Each row represents one product in an order.
    //  DESC    : Line items for each order. Each row represents one product in an order.
    //  TEXT    : Table: orderdetails. Description: Line items for each order. Each row represents one product in an order.. Columns: orderdetailid (serial) [PK]; orderid (integer) [FK -> orders.orderid]; productid (integer) [FK -> products.productid]; quantity (integer); unitprice (numeric); linetotal (numeric); Column descriptions: orderdetailid: orderdetailid (serial). orderid: orderid (integer). productid: productid (integer). quantity: quantity (integer). unitprice: unitprice (numeric). linetotal: linetotal (numeric).
    //--- END OF SCHEMA CHUNKS ---
    //
    //Natural-language question: List all the products which is heavier than 1 kg in decimal
    //
    //SQL:
    //
    //══════════════ END OF PROMPT ══════════════
    //2026-03-25T09:21:43.368-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : ══════════════ LLM RESPONSE ══════════════
    //```sql
    //SELECT DISTINCT pav.attributeid
    //FROM productattributevalues pav
    //JOIN attributes a ON pav.attributeid = a.attributeid
    //WHERE a.name = 'Weight' AND CAST(pav.value AS DECIMAL) > 1;
    //```
    //══════════════ END OF LLM RESPONSE ══════════════
    //2026-03-25T09:21:43.368-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ LLM generated SQL:
    //══════════════════════════════
    //SELECT DISTINCT pav.attributeid
    //FROM productattributevalues pav
    //JOIN attributes a ON pav.attributeid = a.attributeid
    //WHERE a.name = 'Weight' AND CAST(pav.value AS DECIMAL) > 1;
    //══════════════════════════════
    //2026-03-25T09:21:43.370-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLValidationService  : SQL validation: valid=true, errors=[], warnings=[EAV table 'productattributevalues' is referenced, but the attribute name column 'attribute_name' does not appear in the SQL. Did the LLM use an attribute key as a column name? Expected pattern: WHERE attribute_name = '<key>']
    //2026-03-25T09:21:43.374-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.ConfidenceScoreService   : Confidence: semantic=0.438(×0.40) | coverage=0.667(×0.20) | validity=1.0(×0.20) | tableMatch=0.667(×0.20) | → total=0.642 [MEDIUM]
    //2026-03-25T09:21:43.374-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION PROMPT SENT TO LLM ══════════════
    //1. You are a helpful data analyst. Given the SQL query below, write a concise,
    //plain-English explanation (2–4 sentences) describing:
    //2. Which data is being retrieved or modified.
    //3. What filters or conditions apply.
    //4. How the results are ordered or grouped (if any).
    //5. The business purpose this query is likely serving.
    //
    //Do NOT include the SQL in your answer. Use simple language a non-technical
    //business user would understand.
    //
    //SQL:
    //SELECT DISTINCT pav.attributeid
    //FROM productattributevalues pav
    //JOIN attributes a ON pav.attributeid = a.attributeid
    //WHERE a.name = 'Weight' AND CAST(pav.value AS DECIMAL) > 1;
    //══════════════ END OF EXPLANATION PROMPT ══════════════
    //2026-03-25T09:21:45.448-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION LLM RESPONSE ══════════════
    //This query retrieves a list of unique attribute IDs from a database, specifically focusing on product attributes related to weight. It filters the data to include only those products where the weight is greater than 1. The results are not ordered or grouped but are distinct, meaning each attribute ID appears only once. This query likely helps the business identify products that are heavier than a certain threshold, which could be useful for inventory management or shipping logistics.
    //══════════════ END OF EXPLANATION LLM RESPONSE ══════════════
    //2026-03-25T09:21:45.450-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ NL→SQL pipeline done | valid=true | confidence=0.642 [MEDIUM] | piiTokens=0
    //2026-03-25T09:21:45.458-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.TranscriptService   : Transcript appended → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts_03_25_26.json (7 entries total)
    //2026-03-25T09:21:45.461-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.TranscriptService   : request userName='AshokSekar' resolved to userId=1000
    //2026-03-25T09:21:45.471-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.repository.UserRepository   : Transcript inserted | id=1092 | userId=1000 | status=SUCCESS | dataSource=ecommerce
    //2026-03-25T09:21:45.471-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.rag.seek.service.TranscriptService   : Transcript persisted to DB with id=1092
    //2026-03-25T09:21:45.486-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] com.nlp.rag.seek.service.UserDbService   : transcript_json JDBC type=org.postgresql.util.PGobject value-preview='{"remarks": "", "userPrompt": "List all the products which is heavier than 1 kg in decimal", "explanation": "This query '
    //2026-03-25T09:21:45.496-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] com.nlp.rag.seek.service.UserDbService   : Exported 62 transcripts → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json for user='AshokSekar'
    //2026-03-25T09:21:45.496-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T09:21:45.669-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg in decimal' → 'get 1 decimal heavier kg product than'
    //2026-03-25T09:21:45.682-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-1/norm] key='genqry:cache:ecommerce:norm:ef3d36489796e229' canonical='get 1 decimal heavier kg product than' query='List all the products which is heavier than 1 kg in decimal'
    //2026-03-25T09:21:45.684-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-2/vec] key='genqry:cache:ecommerce:vec:872fe998-37e9-4350-a99f-84f1b19cdfe9' query='List all the products which is heavier than 1 kg in decimal'
    //2026-03-25T09:21:45.685-05:00  INFO 55319 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=false | executed=false
    //2026-03-25T09:21:45.703-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-5] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:21:45.703-05:00  INFO 55319 --- [genQry] [nio-9095-exec-5] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T09:21:45.705-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-5] c.n.r.s.c.QueryHistoryController         : Loaded 62 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T09:25:52.883-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-7] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:25:52.889-05:00  INFO 55319 --- [genQry] [nio-9095-exec-7] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg
    //2026-03-25T09:25:52.889-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-7] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T09:25:52.890-05:00  INFO 55319 --- [genQry] [nio-9095-exec-7] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T09:25:52.890-05:00  INFO 55319 --- [genQry] [nio-9095-exec-7] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T09:25:52.890-05:00  INFO 55319 --- [genQry] [nio-9095-exec-7] c.n.r.s.s.SQLGenerationService_BKP0325   : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg' | db='ecommerce' | topK=3
    //2026-03-25T09:25:52.890-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-7] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T09:25:52.890-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-7] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:25:52.902-05:00  INFO 55319 --- [genQry] [nio-9095-exec-7] c.n.r.seek.service.SemanticCacheService  : ✅ Tier-1 HIT [norm-key] key='genqry:cache:ecommerce:norm:5ac20691dbc25d94' canonical='get 1 heavier kg product than' hits=1
    //2026-03-25T09:25:52.903-05:00  INFO 55319 --- [genQry] [nio-9095-exec-7] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ Cache HIT — returning cached response (similarity=1.0000, hits=1)
    //2026-03-25T09:25:52.903-05:00  INFO 55319 --- [genQry] [nio-9095-exec-7] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=true | executed=false
    //2026-03-25T09:25:52.914-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:25:52.916-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T09:25:52.920-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.c.QueryHistoryController         : Loaded 62 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T09:25:56.601-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-9] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:25:56.601-05:00 DEBUG 55319 --- [genQry] [io-9095-exec-10] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:25:56.643-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-2] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:25:59.105-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-1] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:25:59.109-05:00  INFO 55319 --- [genQry] [nio-9095-exec-1] c.n.rag.seek.service.CacheAdminService   : 🗑  Bulk evict: 4 entries (ALL) deleted
    //2026-03-25T09:25:59.109-05:00  WARN 55319 --- [genQry] [nio-9095-exec-1] c.n.r.s.controller.CacheAdminController  : ⚠️  FULL CACHE FLUSH — 4 entries deleted
    //2026-03-25T09:25:59.121-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-3] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:25:59.121-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-4] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:25:59.152-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-5] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:26:01.336-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-7] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:26:01.336-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-6] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:26:01.336-05:00  INFO 55319 --- [genQry] [nio-9095-exec-6] c.n.r.s.controller.WorkspaceController   : GET /api/v1/workSpace — username='AshokSekar' usertype='Root'
    //2026-03-25T09:26:01.336-05:00  INFO 55319 --- [genQry] [nio-9095-exec-7] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T09:26:01.339-05:00  INFO 55319 --- [genQry] [nio-9095-exec-6] c.n.r.s.controller.WorkspaceController   : Workspace: user='AshokSekar' usertype='Root' isGuest=false → fileList=[{name=ecommerce, type=database}, {name=2021.pdf, type=document}, {name=2023.pdf, type=document}, {name=AshokkumarSeka.docx, type=document}, {name=AshokW2.pdf, type=document}]
    //2026-03-25T09:26:01.340-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-7] c.n.r.s.c.QueryHistoryController         : Loaded 62 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T09:26:13.996-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:26:13.997-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg
    //2026-03-25T09:26:13.998-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T09:26:13.998-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T09:26:13.998-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T09:26:13.998-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg' | db='ecommerce' | topK=3
    //2026-03-25T09:26:13.998-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T09:26:13.998-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:26:13.999-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:26:13.999-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SemanticCacheService  : Tier-1 MISS key='genqry:cache:ecommerce:norm:5ac20691dbc25d94'  canonical='get 1 heavier kg product than'
    //2026-03-25T09:26:14.271-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SemanticCacheService  : Tier-2 MISS — no vector entries for db='ecommerce'
    //2026-03-25T09:26:14.422-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.VectorStoreService  : Semantic search 'List all the products which is heavier than 1 kg' → top 3 results:
    //2026-03-25T09:26:14.423-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2993300870258174 | table=orderdetails#0 col=orderdetails
    //2026-03-25T09:26:14.423-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.29690434090369383 | table=products#0 col=products
    //2026-03-25T09:26:14.423-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.24062045843039984 | table=productsuppliers.productid#0 col=productsuppliers
    //2026-03-25T09:26:14.423-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'orders' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T09:26:14.423-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'categories' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T09:26:14.423-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'suppliers' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T09:26:14.424-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : EAV-injection: force-injected EAV table 'attributes' for query terms [weight]
    //2026-03-25T09:26:14.424-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : EAV-injection: force-injected EAV table 'productattributevalues' for query terms [weight]
    //2026-03-25T09:26:14.424-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : TopK cap applied: 8 chunks → 3 (topK=3)
    //2026-03-25T09:26:14.425-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping attributes#0 [score=0.500] for table 'attributes'
    //2026-03-25T09:26:14.425-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping productattributevalues#0 [score=0.500] for table 'productattributevalues'
    //2026-03-25T09:26:14.425-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping orderdetails#0 [score=0.299] for table 'orderdetails'
    //2026-03-25T09:26:14.425-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : Sending sanitized query to LLM with top-3 schema chunks
    //2026-03-25T09:26:14.425-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : ══════════════ PROMPT SENT TO LLM ══════════════
    //You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query..
    //
    //Rules:
    //1. Use ONLY the tables and columns that appear in the schema context below.
    //2. Write standard SQL compatible with PostgreSQL.
    //3. Use explicit JOINs with ON clauses — never implicit comma joins.
    //4. Add a WHERE clause whenever a filter is implied.
    //5. Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) when appropriate.
    //6. Add ORDER BY when a ranking or 'top N' is implied.
    //7. Return ONLY the SQL statement — no prose, no markdown fences.
    //8. CRITICAL JOIN RULE — if two tables doesnt have foreign key relationship then dont join them
    //10. MPORTANT: ALL tables and columns listed in the schema context ARE valid and exist in the database — you MUST use them if they match the user's question
    //11. End the sql statement with a semicolon.
    //
    //Follow the below rules for EAV tables:
    //1. NEVER use attribute names (e.g. Weight, Color, Size) as SQL column names.
    //2. Use WHERE <attr_name_col> = '<key>' to select a specific attribute.
    //3. Use CAST(<attr_value_col> AS <type>) when reading numeric or date values.
    //4. For multiple attributes in one row, use conditional aggregation: MAX(CASE WHEN <attr_name_col> = '<key1>' THEN CAST(<attr_value_col> AS <type>) END) AS <key1>
    //5. Join to the entity table via the entity_id column shown in the context.
    //6. EAV attributes retrieved for this query are listed in the schema context — use their FILTER and CAST HINT values exactly.
    //7. Attribute names are stored as ROW VALUES in the attribute name column.
    //8. Join the entity table to the EAV data table on the entity ID column.
    //9. Join the EAV data table to the attributes table on the attribute ID column.
    //
    //Database: ecommerce
    //
    //IMPORTANT: ALL tables listed below are valid and exist in the database. You MUST use any table or column from the schema context if it is relevant to the question. Only respond with DATA_NOT_AVAILABLE if the user's question refers to a concept that has NO matching table or column ANYWHERE in the schema context below.
    //
    //--- RETRIEVED SCHEMA CHUNKS ---
    //• attributes#0
    //  TABLE         : attributes
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : name
    //  ATTR VALUE COL: datatype
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.
    //  TEXT          : Table: attributes. Description: EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE name = '<attribute_key>' to filter, and read datatype for the value. Entity linked via attributeid. Columns: attributeid (serial) [PK]; name (character varying); datatype (character varying); unit (character varying); description (text); createdat (timestamp); Column descriptions: attributeid:
    //
    //• productattributevalues#0
    //  TABLE         : productattributevalues
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : attribute_name
    //  ATTR VALUE COL: value
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE attribute_name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.
    //  TEXT          : Table: productattributevalues. Description: EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE attribute_name = '<attribute_key>' to filter, and read value for the value. Entity linked via attributeid
    //
    //• orderdetails#0
    //  TABLE   : orderdetails
    //  COLUMNS : orderdetailid:serial, orderid:integer, productid:integer, quantity:integer, unitprice:numeric, linetotal:numeric
    //  PKs     : orderdetailid
    //  FKs     : orderid->orders.orderid, productid->products.productid
    //  CONTEXT : Line items for each order. Each row represents one product in an order.
    //  DESC    : Line items for each order. Each row represents one product in an order.
    //  TEXT    : Table: orderdetails. Description: Line items for each order. Each row represents one product in an order.. Columns: orderdetailid (serial) [PK]; orderid (integer) [FK -> orders.orderid]; productid (integer) [FK -> products.productid]; quantity (integer); unitprice (numeric); linetotal (numeric); Column descriptions: orderdetailid: orderdetailid (serial). orderid: orderid (integer). productid: productid (integer). quantity: quantity (integer). unitprice: unitprice (numeric). linetotal: linetotal (numeric).
    //--- END OF SCHEMA CHUNKS ---
    //
    //Natural-language question: List all the products which is heavier than 1 kg
    //
    //SQL:
    //
    //══════════════ END OF PROMPT ══════════════
    //2026-03-25T09:26:16.058-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : ══════════════ LLM RESPONSE ══════════════
    //DATA_NOT_AVAILABLE
    //══════════════ END OF LLM RESPONSE ══════════════
    //2026-03-25T09:26:16.058-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ LLM generated SQL:
    //══════════════════════════════
    //DATA_NOT_AVAILABLE
    //══════════════════════════════
    //2026-03-25T09:26:16.059-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SQLValidationService  : SQL validation: valid=false, errors=[SQL does not start with a recognised keyword (SELECT/INSERT/UPDATE/DELETE/WITH).], warnings=[SQL does not end with a semicolon.]
    //2026-03-25T09:26:16.059-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.ConfidenceScoreService   : Confidence: semantic=0.433(×0.40) | coverage=0.667(×0.20) | validity=0.0(×0.20) | tableMatch=0.000(×0.20) | → total=0.307 [LOW]
    //2026-03-25T09:26:16.059-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION PROMPT SENT TO LLM ══════════════
    //1. You are a helpful data analyst. Given the SQL query below, write a concise,
    //plain-English explanation (2–4 sentences) describing:
    //2. Which data is being retrieved or modified.
    //3. What filters or conditions apply.
    //4. How the results are ordered or grouped (if any).
    //5. The business purpose this query is likely serving.
    //
    //Do NOT include the SQL in your answer. Use simple language a non-technical
    //business user would understand.
    //
    //SQL:
    //DATA_NOT_AVAILABLE
    //══════════════ END OF EXPLANATION PROMPT ══════════════
    //2026-03-25T09:26:17.195-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION LLM RESPONSE ══════════════
    //I'm sorry, but I can't provide an explanation without the SQL query. If you can provide the query, I'd be happy to help explain it in simple terms.
    //══════════════ END OF EXPLANATION LLM RESPONSE ══════════════
    //2026-03-25T09:26:17.196-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ NL→SQL pipeline done | valid=false | confidence=0.307 [LOW] | piiTokens=0
    //2026-03-25T09:26:17.198-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.TranscriptService   : Transcript appended → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts_03_25_26.json (8 entries total)
    //2026-03-25T09:26:17.201-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.TranscriptService   : request userName='AshokSekar' resolved to userId=1000
    //2026-03-25T09:26:17.203-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.repository.UserRepository   : Transcript inserted | id=1093 | userId=1000 | status=FAILURE | dataSource=ecommerce
    //2026-03-25T09:26:17.203-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.rag.seek.service.TranscriptService   : Transcript persisted to DB with id=1093
    //2026-03-25T09:26:17.208-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] com.nlp.rag.seek.service.UserDbService   : transcript_json JDBC type=org.postgresql.util.PGobject value-preview='{"remarks": "", "userPrompt": "List all the products which is heavier than 1 kg", "explanation": "I'm sorry, but I can't'
    //2026-03-25T09:26:17.214-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] com.nlp.rag.seek.service.UserDbService   : Exported 63 transcripts → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json for user='AshokSekar'
    //2026-03-25T09:26:17.214-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:26:17.425-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-8] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:26:17.429-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-1/norm] key='genqry:cache:ecommerce:norm:5ac20691dbc25d94' canonical='get 1 heavier kg product than' query='List all the products which is heavier than 1 kg'
    //2026-03-25T09:26:17.430-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-2/vec] key='genqry:cache:ecommerce:vec:b88f4789-a8b8-411a-a0d3-a57d5714d65a' query='List all the products which is heavier than 1 kg'
    //2026-03-25T09:26:17.431-05:00  INFO 55319 --- [genQry] [nio-9095-exec-8] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=false | executed=false
    //2026-03-25T09:26:17.449-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-9] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:26:17.450-05:00  INFO 55319 --- [genQry] [nio-9095-exec-9] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T09:26:17.451-05:00 DEBUG 55319 --- [genQry] [nio-9095-exec-9] c.n.r.s.c.QueryHistoryController         : Loaded 63 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //^C%                                                                                                                                                                                                              ashoksekar@ashoks-MacBook-Pro seek % ./mvnw clean package -DskipTests -q && java -jar target/genQry-0.0.1-SNAPSHOT.jar
    //
    //  .   ____          _            __ _ _
    // /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
    //( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
    // \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
    //  '  |____| .__|_| |_|_| |_\__, | / / / /
    // =========|_|==============|___/=/_/_/_/
    // :: Spring Boot ::                (v3.2.5)
    //
    //2026-03-25T09:41:22.998-05:00  INFO 56382 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Starting SeekApplication v0.0.1-SNAPSHOT using Java 17.0.18 with PID 56382 (/Users/ashoksekar/workspace/genAi/seek/target/genQry-0.0.1-SNAPSHOT.jar started by ashoksekar in /Users/ashoksekar/workspace/genAi/seek)
    //2026-03-25T09:41:23.000-05:00 DEBUG 56382 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Running with Spring Boot v3.2.5, Spring v6.1.6
    //2026-03-25T09:41:23.001-05:00  INFO 56382 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : No active profile set, falling back to 1 default profile: "default"
    //2026-03-25T09:41:24.182-05:00  INFO 56382 --- [genQry] [           main] c.nlp.rag.seek.config.JwtTokenProvider   : JwtTokenProvider initialised — expiration=86400000ms
    //2026-03-25T09:41:24.203-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.config.JwtAuthenticationFilter   : Filter 'jwtAuthenticationFilter' configured for use
    //2026-03-25T09:41:24.299-05:00  INFO 56382 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → PRIMARY (genQry) set to read-only mode
    //2026-03-25T09:41:24.302-05:00  INFO 56382 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → PRIMARY (genQry) reachable
    //2026-03-25T09:41:24.631-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.service.SQLExplanationService    : Explanation rules loaded from BusinessRulesForPrompts.json — 321 chars
    //2026-03-25T09:41:24.643-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    : Abbreviations loaded from supportingFiles/abbreviations.json — 152 entries across all sections
    //2026-03-25T09:41:24.644-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'employee' → 8 synonym(s)
    //2026-03-25T09:41:24.644-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'customer' → 5 synonym(s)
    //2026-03-25T09:41:24.644-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'order' → 5 synonym(s)
    //2026-03-25T09:41:24.644-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'product' → 5 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'department' → 5 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'invoice' → 4 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'payment' → 5 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'salary' → 5 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'address' → 4 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'account' → 4 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'vendor' → 4 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'category' → 4 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'transaction' → 4 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'revenue' → 4 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'headcount' → 4 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'recent orders' → 4 synonym(s)
    //2026-03-25T09:41:24.645-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'inventory' → 4 synonym(s)
    //2026-03-25T09:41:24.646-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    : Semantic alias groups loaded from supportingFiles/semantic.json — 17 groups
    //2026-03-25T09:41:24.650-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.MetadataDirectoryResolver      : MetadataDirectoryResolver ready — default dir: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/metadata, supportingFiles: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles
    //2026-03-25T09:41:24.654-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : ═══ RAG pipeline startup ═══
    //2026-03-25T09:41:24.654-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Root schema exists at '/Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/root/root_ecommerce_DBSchema.json' — initialising RAG from file…
    //2026-03-25T09:41:24.655-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.MetadataDirectoryResolver      : Latest schema filename registered — database='ecommerce' → file='root_ecommerce_DBSchema.json'
    //2026-03-25T09:41:24.655-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.service.SchemaExtractionService  : Building activeSchema from JSON file for database 'ecommerce'
    //2026-03-25T09:41:24.655-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.service.SchemaFileReaderService  : Using root ecommerce schema file: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/root/root_ecommerce_DBSchema.json
    //2026-03-25T09:41:24.669-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.service.SchemaFileReaderService  : Loaded schema file: root_ecommerce_DBSchema.json
    //2026-03-25T09:41:24.670-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.service.SchemaExtractionService  : buildActiveSchemaFromJson: 'ecommerce' — 9 table(s) loaded from JSON (business_context preserved for all tables)
    //2026-03-25T09:41:24.671-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'attributes.name' → 'name'
    //2026-03-25T09:41:24.671-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'attributes.unit' → 'unit'
    //2026-03-25T09:41:24.672-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'categories.name' → 'name'
    //2026-03-25T09:41:24.672-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'customers.name' → 'name'
    //2026-03-25T09:41:24.673-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'products.name' → 'name'
    //2026-03-25T09:41:24.673-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'products.sku' → 'sku'
    //2026-03-25T09:41:24.674-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'suppliers.name' → 'name'
    //2026-03-25T09:41:24.674-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  : Schema enrichment complete — 0 tables enriched, 7 columns enriched (strategy=AUTO)
    //2026-03-25T09:41:24.676-05:00  INFO 56382 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : Table 'attributes' identified as EAV (signals=6) — entityId='attributeid', attrName='name', attrValue='datatype'
    //2026-03-25T09:41:24.677-05:00  INFO 56382 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : Table 'productattributevalues' identified as EAV (signals=4) — entityId='attributeid', attrName='attribute_name', attrValue='value'
    //2026-03-25T09:41:24.677-05:00  INFO 56382 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : EAV detection complete — 2 EAV/vertical table(s) identified in schema 'ecommerce'
    //2026-03-25T09:41:24.679-05:00 DEBUG 56382 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : EAV table 'attributes' has no known_attributes catalogued — only TABLE/COLUMN chunks produced
    //2026-03-25T09:41:24.681-05:00 DEBUG 56382 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : EAV table 'productattributevalues' has no known_attributes catalogued — only TABLE/COLUMN chunks produced
    //2026-03-25T09:41:24.682-05:00  INFO 56382 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : Chunking complete: 76 metadata-enriched chunks from schema 'ecommerce' (chunkSize=80, stride=40, overlap=40)
    //2026-03-25T09:41:24.682-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Ecommerce schema loaded from file: 9 tables, 76 chunks
    //2026-03-25T09:41:24.682-05:00  INFO 56382 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Indexing 76 metadata-enriched chunks for database 'ecommerce'...
    //2026-03-25T09:41:46.173-05:00  INFO 56382 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Indexed 76 chunks (76 with embeddings, 0 keyword-only) into vector store
    //2026-03-25T09:41:46.173-05:00  INFO 56382 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Semantic search ENABLED — embedding model active
    //2026-03-25T09:41:46.239-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.VectorIndexPersistenceService  : Vector index persisted → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/vector_index_ecommerce.json (76 chunks)
    //2026-03-25T09:41:46.239-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Vector store ready — 76 entries indexed (SEMANTIC mode) user='root'
    //2026-03-25T09:41:46.239-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : ═══ RAG pipeline ready (ecommerce from file) ═══
    //2026-03-25T09:41:46.254-05:00  INFO 56382 --- [genQry] [           main] com.nlp.rag.seek.config.RedisConfig      : Redis connection factory configured → localhost:6379
    //2026-03-25T09:41:46.311-05:00  INFO 56382 --- [genQry] [           main] com.nlp.rag.seek.config.RedisConfig      : RedisTemplate<String, CacheEntry> configured with Jackson JSON serializer
    //2026-03-25T09:41:46.320-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.service.FernetEncryptionService  : Fernet: loaded key from configuration
    //2026-03-25T09:41:46.321-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.SQLGenerationService_BKP0325   : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=732 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T09:41:46.707-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.doc.service.DocumentRagService   : Doc business rules loaded from supportingFiles/BusinessRulesForPrompts.json — 571 chars
    //2026-03-25T09:41:46.741-05:00  INFO 56382 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → SECONDARY (ecommerce) reachable
    //2026-03-25T09:41:46.759-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.SQLGenerationService_BKP0325   : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=732 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T09:41:46.791-05:00  WARN 56382 --- [genQry] [           main] .s.s.UserDetailsServiceAutoConfiguration :
    //
    //Using generated security password: 1887c027-088b-41c9-b9e9-b9ff94749c06
    //
    //This generated password is for development use only. Your security configuration must be updated before running your application in production.
    //
    //2026-03-25T09:41:47.152-05:00  INFO 56382 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Started SeekApplication in 24.392 seconds (process running for 24.691)
    //2026-03-25T09:41:47.154-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : Running genQry DB migrations…
    //2026-03-25T09:41:47.217-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : Migration step (skipped): StatementCallback; bad SQL grammar [CREATE INDEX IF NOT EXISTS idx_transcript_session  ON transcript_details(session_id)]
    //2026-03-25T09:41:47.221-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : business_rules table already seeded (35 rows) — skipping
    //2026-03-25T09:41:47.222-05:00 DEBUG 56382 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : DATA_NOT_AVAILABLE rule already exists — skipping
    //2026-03-25T09:41:47.222-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : genQry DB migrations complete ✅
    //2026-03-25T09:41:47.243-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : BusinessRulesForPrompts.json exported — 35 rules → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/BusinessRulesForPrompts.json
    //2026-03-25T09:41:47.244-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.s.SQLGenerationService_BKP0325   : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=732 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T09:41:47.246-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : SQLGenerationService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T09:41:47.246-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.service.SQLExplanationService    : Explanation rules loaded from BusinessRulesForPrompts.json — 321 chars
    //2026-03-25T09:41:47.246-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : SQLExplanationService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T09:41:47.247-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.doc.service.DocumentRagService   : Doc business rules loaded from supportingFiles/BusinessRulesForPrompts.json — 571 chars
    //2026-03-25T09:41:47.247-05:00  INFO 56382 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : DocumentRagService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T09:41:58.988-05:00  INFO 56382 --- [genQry] [nio-9095-exec-1] c.n.rag.seek.controller.AuthController   : POST /api/v1/auth/token — username='AshokSekar'
    //2026-03-25T09:41:59.042-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-1] c.nlp.rag.seek.config.JwtTokenProvider   : JWT generated for email='mailashoky@gmail.com' username='AshokSekar' userType='Root' expires=Thu Mar 26 09:41:59 CDT 2026
    //2026-03-25T09:41:59.043-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:41:59.043-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-2] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:41:59.053-05:00  INFO 56382 --- [genQry] [nio-9095-exec-2] c.n.r.s.controller.WorkspaceController   : GET /api/v1/workSpace — username='AshokSekar' usertype='Root'
    //2026-03-25T09:41:59.053-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T09:41:59.056-05:00  INFO 56382 --- [genQry] [nio-9095-exec-2] c.n.r.s.controller.WorkspaceController   : Workspace: user='AshokSekar' usertype='Root' isGuest=false → fileList=[{type=database, name=ecommerce}, {type=document, name=2021.pdf}, {type=document, name=2023.pdf}, {type=document, name=AshokkumarSeka.docx}, {type=document, name=AshokW2.pdf}]
    //2026-03-25T09:41:59.070-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.c.QueryHistoryController         : Loaded 63 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T09:42:23.784-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-4] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:42:23.784-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-5] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:42:23.960-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-6] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:42:26.245-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-7] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:42:26.249-05:00  INFO 56382 --- [genQry] [nio-9095-exec-7] c.n.rag.seek.service.CacheAdminService   : 🗑  Bulk evict: 2 entries (ALL) deleted
    //2026-03-25T09:42:26.249-05:00  WARN 56382 --- [genQry] [nio-9095-exec-7] c.n.r.s.controller.CacheAdminController  : ⚠️  FULL CACHE FLUSH — 2 entries deleted
    //2026-03-25T09:42:26.263-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-9] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:42:26.262-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-8] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:42:26.286-05:00 DEBUG 56382 --- [genQry] [io-9095-exec-10] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:42:28.129-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-1] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:42:28.129-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-2] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:42:28.130-05:00  INFO 56382 --- [genQry] [nio-9095-exec-1] c.n.r.s.controller.WorkspaceController   : GET /api/v1/workSpace — username='AshokSekar' usertype='Root'
    //2026-03-25T09:42:28.130-05:00  INFO 56382 --- [genQry] [nio-9095-exec-2] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T09:42:28.130-05:00  INFO 56382 --- [genQry] [nio-9095-exec-1] c.n.r.s.controller.WorkspaceController   : Workspace: user='AshokSekar' usertype='Root' isGuest=false → fileList=[{type=database, name=ecommerce}, {type=document, name=2021.pdf}, {type=document, name=2023.pdf}, {type=document, name=AshokkumarSeka.docx}, {type=document, name=AshokW2.pdf}]
    //2026-03-25T09:42:28.133-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-2] c.n.r.s.c.QueryHistoryController         : Loaded 63 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T09:42:33.318-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:42:33.322-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg
    //2026-03-25T09:42:33.322-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T09:42:33.322-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T09:42:33.322-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T09:42:33.322-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg' | db='ecommerce' | topK=3
    //2026-03-25T09:42:33.323-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T09:42:33.324-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:42:33.326-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:42:33.326-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.seek.service.SemanticCacheService  : Tier-1 MISS key='genqry:cache:ecommerce:norm:5ac20691dbc25d94'  canonical='get 1 heavier kg product than'
    //2026-03-25T09:42:33.485-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.seek.service.SemanticCacheService  : Tier-2 MISS — no vector entries for db='ecommerce'
    //2026-03-25T09:42:33.679-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.rag.seek.service.VectorStoreService  : Semantic search 'List all the products which is heavier than 1 kg' → top 3 results:
    //2026-03-25T09:42:33.679-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2992930265002506 | table=orderdetails#0 col=orderdetails
    //2026-03-25T09:42:33.679-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.29692226171672453 | table=products#0 col=products
    //2026-03-25T09:42:33.679-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2405617458251739 | table=productsuppliers.productid#0 col=productsuppliers
    //2026-03-25T09:42:33.680-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'orders' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T09:42:33.680-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'categories' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T09:42:33.680-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : FK-expansion: injected table 'suppliers' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers])
    //2026-03-25T09:42:33.682-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : EAV-injection: force-injected EAV table 'attributes' for query terms [weight]
    //2026-03-25T09:42:33.682-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : EAV-injection: force-injected EAV table 'productattributevalues' for query terms [weight]
    //2026-03-25T09:42:33.682-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : TopK cap applied: 8 chunks → 3 (topK=3)
    //2026-03-25T09:42:33.682-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping attributes#0 [score=0.500] for table 'attributes'
    //2026-03-25T09:42:33.682-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping productattributevalues#0 [score=0.500] for table 'productattributevalues'
    //2026-03-25T09:42:33.683-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : Dedup: Keeping orderdetails#0 [score=0.299] for table 'orderdetails'
    //2026-03-25T09:42:33.683-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : Sending sanitized query to LLM with top-3 schema chunks
    //2026-03-25T09:42:33.685-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : ══════════════ PROMPT SENT TO LLM ══════════════
    //You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query..
    //
    //Rules:
    //1. Use ONLY the tables and columns that appear in the schema context below.
    //2. Write standard SQL compatible with PostgreSQL.
    //3. Use explicit JOINs with ON clauses — never implicit comma joins.
    //4. Add a WHERE clause whenever a filter is implied.
    //5. Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) when appropriate.
    //6. Add ORDER BY when a ranking or 'top N' is implied.
    //7. Return ONLY the SQL statement — no prose, no markdown fences.
    //8. CRITICAL JOIN RULE — if two tables doesnt have foreign key relationship then dont join them
    //10. MPORTANT: ALL tables and columns listed in the schema context ARE valid and exist in the database — you MUST use them if they match the user's question
    //11. End the sql statement with a semicolon.
    //
    //Follow the below rules for EAV tables:
    //1. NEVER use attribute names (e.g. Weight, Color, Size) as SQL column names.
    //2. Use WHERE <attr_name_col> = '<key>' to select a specific attribute.
    //3. Use CAST(<attr_value_col> AS <type>) when reading numeric or date values.
    //4. For multiple attributes in one row, use conditional aggregation: MAX(CASE WHEN <attr_name_col> = '<key1>' THEN CAST(<attr_value_col> AS <type>) END) AS <key1>
    //5. Join to the entity table via the entity_id column shown in the context.
    //6. EAV attributes retrieved for this query are listed in the schema context — use their FILTER and CAST HINT values exactly.
    //7. Attribute names are stored as ROW VALUES in the attribute name column.
    //8. Join the entity table to the EAV data table on the entity ID column.
    //9. Join the EAV data table to the attributes table on the attribute ID column.
    //
    //Database: ecommerce
    //
    //IMPORTANT: ALL tables listed below are valid and exist in the database. You MUST use any table or column from the schema context if it is relevant to the question. Only respond with DATA_NOT_AVAILABLE if the user's question refers to a concept that has NO matching table or column ANYWHERE in the schema context below.
    //
    //--- RETRIEVED SCHEMA CHUNKS ---
    //• attributes#0
    //  TABLE         : attributes
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : name
    //  ATTR VALUE COL: datatype
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.
    //  TEXT          : Table: attributes. Description: EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE name = '<attribute_key>' to filter, and read datatype for the value. Entity linked via attributeid. Columns: attributeid (serial) [PK]; name (character varying); datatype (character varying); unit (character varying); description (text); createdat (timestamp); Column descriptions: attributeid:
    //
    //• productattributevalues#0
    //  TABLE         : productattributevalues
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : attribute_name
    //  ATTR VALUE COL: value
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE attribute_name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.
    //  TEXT          : Table: productattributevalues. Description: EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE attribute_name = '<attribute_key>' to filter, and read value for the value. Entity linked via attributeid
    //
    //• orderdetails#0
    //  TABLE   : orderdetails
    //  COLUMNS : orderdetailid:serial, orderid:integer, productid:integer, quantity:integer, unitprice:numeric, linetotal:numeric
    //  PKs     : orderdetailid
    //  FKs     : orderid->orders.orderid, productid->products.productid
    //  CONTEXT : Line items for each order. Each row represents one product in an order.
    //  DESC    : Line items for each order. Each row represents one product in an order.
    //  TEXT    : Table: orderdetails. Description: Line items for each order. Each row represents one product in an order.. Columns: orderdetailid (serial) [PK]; orderid (integer) [FK -> orders.orderid]; productid (integer) [FK -> products.productid]; quantity (integer); unitprice (numeric); linetotal (numeric); Column descriptions: orderdetailid: orderdetailid (serial). orderid: orderid (integer). productid: productid (integer). quantity: quantity (integer). unitprice: unitprice (numeric). linetotal: linetotal (numeric).
    //--- END OF SCHEMA CHUNKS ---
    //
    //Natural-language question: List all the products which is heavier than 1 kg
    //
    //SQL:
    //
    //══════════════ END OF PROMPT ══════════════
    //2026-03-25T09:42:34.235-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : ══════════════ LLM RESPONSE ══════════════
    //DATA_NOT_AVAILABLE
    //══════════════ END OF LLM RESPONSE ══════════════
    //2026-03-25T09:42:34.235-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ LLM generated SQL:
    //══════════════════════════════
    //DATA_NOT_AVAILABLE
    //══════════════════════════════
    //2026-03-25T09:42:34.236-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.seek.service.SQLValidationService  : SQL validation: valid=false, errors=[SQL does not start with a recognised keyword (SELECT/INSERT/UPDATE/DELETE/WITH).], warnings=[SQL does not end with a semicolon.]
    //2026-03-25T09:42:34.238-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.service.ConfidenceScoreService   : Confidence: semantic=0.433(×0.40) | coverage=0.667(×0.20) | validity=0.0(×0.20) | tableMatch=0.000(×0.20) | → total=0.307 [LOW]
    //2026-03-25T09:42:34.238-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION PROMPT SENT TO LLM ══════════════
    //1. You are a helpful data analyst. Given the SQL query below, write a concise,
    //plain-English explanation (2–4 sentences) describing:
    //2. Which data is being retrieved or modified.
    //3. What filters or conditions apply.
    //4. How the results are ordered or grouped (if any).
    //5. The business purpose this query is likely serving.
    //
    //Do NOT include the SQL in your answer. Use simple language a non-technical
    //business user would understand.
    //
    //SQL:
    //DATA_NOT_AVAILABLE
    //══════════════ END OF EXPLANATION PROMPT ══════════════
    //2026-03-25T09:42:35.055-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION LLM RESPONSE ══════════════
    //I'm sorry, but I can't provide an explanation without the SQL query. If you can provide the SQL query, I'd be happy to help explain it in simple terms.
    //══════════════ END OF EXPLANATION LLM RESPONSE ══════════════
    //2026-03-25T09:42:35.056-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.s.SQLGenerationService_BKP0325   : ◀ NL→SQL pipeline done | valid=false | confidence=0.307 [LOW] | piiTokens=0
    //2026-03-25T09:42:35.065-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.rag.seek.service.TranscriptService   : Transcript appended → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts_03_25_26.json (9 entries total)
    //2026-03-25T09:42:35.071-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.rag.seek.service.TranscriptService   : request userName='AshokSekar' resolved to userId=1000
    //2026-03-25T09:42:35.083-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.rag.seek.repository.UserRepository   : Transcript inserted | id=1094 | userId=1000 | status=FAILURE | dataSource=ecommerce
    //2026-03-25T09:42:35.083-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.rag.seek.service.TranscriptService   : Transcript persisted to DB with id=1094
    //2026-03-25T09:42:35.102-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] com.nlp.rag.seek.service.UserDbService   : transcript_json JDBC type=org.postgresql.util.PGobject value-preview='{"remarks": "", "userPrompt": "List all the products which is heavier than 1 kg", "explanation": "I'm sorry, but I can't'
    //2026-03-25T09:42:35.115-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] com.nlp.rag.seek.service.UserDbService   : Exported 64 transcripts → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json for user='AshokSekar'
    //2026-03-25T09:42:35.115-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:42:35.256-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-3] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:42:35.272-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-1/norm] key='genqry:cache:ecommerce:norm:5ac20691dbc25d94' canonical='get 1 heavier kg product than' query='List all the products which is heavier than 1 kg'
    //2026-03-25T09:42:35.274-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-2/vec] key='genqry:cache:ecommerce:vec:be321844-74d1-4328-b92b-c3a9cd95935c' query='List all the products which is heavier than 1 kg'
    //2026-03-25T09:42:35.275-05:00  INFO 56382 --- [genQry] [nio-9095-exec-3] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=false | executed=false
    //2026-03-25T09:42:35.294-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-4] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:42:35.295-05:00  INFO 56382 --- [genQry] [nio-9095-exec-4] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T09:42:35.297-05:00 DEBUG 56382 --- [genQry] [nio-9095-exec-4] c.n.r.s.c.QueryHistoryController         : Loaded 64 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //^C%                                                                                                                                                                                                              ashoksekar@ashoks-MacBook-Pro seek % ./mvnw clean package -DskipTests -q && java -jar target/genQry-0.0.1-SNAPSHOT.jar
    //[ERROR] COMPILATION ERROR :
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/service/CacheAdminService.java:[46,24] cannot find symbol
    //  symbol:   class SQLGenerationService_BKP0325
    //  location: class com.nlp.rag.seek.service.CacheAdminService
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/controller/NL2SQLController.java:[13,32] cannot find symbol
    //  symbol:   class SQLGenerationService_BKP0325
    //  location: package com.nlp.rag.seek.service
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/controller/NL2SQLController.java:[52,24] cannot find symbol
    //  symbol:   class SQLGenerationService_BKP0325
    //  location: class com.nlp.rag.seek.controller.NL2SQLController
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/config/BusinessRulesJsonExporter.java:[7,32] cannot find symbol
    //  symbol:   class SQLGenerationService_BKP0325
    //  location: package com.nlp.rag.seek.service
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/config/BusinessRulesJsonExporter.java:[56,13] cannot find symbol
    //  symbol:   class SQLGenerationService_BKP0325
    //  location: class com.nlp.rag.seek.config.BusinessRulesJsonExporter
    //[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile (default-compile) on project seek: Compilation failure: Compilation failure:
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/service/CacheAdminService.java:[46,24] cannot find symbol
    //[ERROR]   symbol:   class SQLGenerationService_BKP0325
    //[ERROR]   location: class com.nlp.rag.seek.service.CacheAdminService
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/controller/NL2SQLController.java:[13,32] cannot find symbol
    //[ERROR]   symbol:   class SQLGenerationService_BKP0325
    //[ERROR]   location: package com.nlp.rag.seek.service
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/controller/NL2SQLController.java:[52,24] cannot find symbol
    //[ERROR]   symbol:   class SQLGenerationService_BKP0325
    //[ERROR]   location: class com.nlp.rag.seek.controller.NL2SQLController
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/config/BusinessRulesJsonExporter.java:[7,32] cannot find symbol
    //[ERROR]   symbol:   class SQLGenerationService_BKP0325
    //[ERROR]   location: package com.nlp.rag.seek.service
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/config/BusinessRulesJsonExporter.java:[56,13] cannot find symbol
    //[ERROR]   symbol:   class SQLGenerationService_BKP0325
    //[ERROR]   location: class com.nlp.rag.seek.config.BusinessRulesJsonExporter
    //[ERROR] -> [Help 1]
    //[ERROR]
    //[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
    //[ERROR] Re-run Maven using the -X switch to enable full debug logging.
    //[ERROR]
    //[ERROR] For more information about the errors and possible solutions, please read the following articles:
    //[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
    //ashoksekar@ashoks-MacBook-Pro seek % ./mvnw clean package -DskipTests -q && java -jar target/genQry-0.0.1-SNAPSHOT.jar
    //[ERROR] COMPILATION ERROR :
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/service/CacheAdminService.java:[46,24] cannot find symbol
    //  symbol:   class SQLGenerationService_BKP0325
    //  location: class com.nlp.rag.seek.service.CacheAdminService
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/controller/NL2SQLController.java:[52,24] cannot find symbol
    //  symbol:   class SQLGenerationService_BKP0325
    //  location: class com.nlp.rag.seek.controller.NL2SQLController
    //[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile (default-compile) on project seek: Compilation failure: Compilation failure:
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/service/CacheAdminService.java:[46,24] cannot find symbol
    //[ERROR]   symbol:   class SQLGenerationService_BKP0325
    //[ERROR]   location: class com.nlp.rag.seek.service.CacheAdminService
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/controller/NL2SQLController.java:[52,24] cannot find symbol
    //[ERROR]   symbol:   class SQLGenerationService_BKP0325
    //[ERROR]   location: class com.nlp.rag.seek.controller.NL2SQLController
    //[ERROR] -> [Help 1]
    //[ERROR]
    //[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
    //[ERROR] Re-run Maven using the -X switch to enable full debug logging.
    //[ERROR]
    //[ERROR] For more information about the errors and possible solutions, please read the following articles:
    //[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
    //ashoksekar@ashoks-MacBook-Pro seek % ./mvnw clean package -DskipTests -q && java -jar target/genQry-0.0.1-SNAPSHOT.jar
    //[ERROR] COMPILATION ERROR :
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/controller/NL2SQLController.java:[52,24] cannot find symbol
    //  symbol:   class SQLGenerationService_BKP0325
    //  location: class com.nlp.rag.seek.controller.NL2SQLController
    //[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile (default-compile) on project seek: Compilation failure
    //[ERROR] /Users/ashoksekar/workspace/genAi/seek/src/main/java/com/nlp/rag/seek/controller/NL2SQLController.java:[52,24] cannot find symbol
    //[ERROR]   symbol:   class SQLGenerationService_BKP0325
    //[ERROR]   location: class com.nlp.rag.seek.controller.NL2SQLController
    //[ERROR]
    //[ERROR] -> [Help 1]
    //[ERROR]
    //[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
    //[ERROR] Re-run Maven using the -X switch to enable full debug logging.
    //[ERROR]
    //[ERROR] For more information about the errors and possible solutions, please read the following articles:
    //[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
    //ashoksekar@ashoks-MacBook-Pro seek % ./mvnw clean package -DskipTests -q && java -jar target/genQry-0.0.1-SNAPSHOT.jar
    //
    //  .   ____          _            __ _ _
    // /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
    //( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
    // \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
    //  '  |____| .__|_| |_|_| |_\__, | / / / /
    // =========|_|==============|___/=/_/_/_/
    // :: Spring Boot ::                (v3.2.5)
    //
    //2026-03-25T09:55:58.863-05:00  INFO 57660 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Starting SeekApplication v0.0.1-SNAPSHOT using Java 17.0.18 with PID 57660 (/Users/ashoksekar/workspace/genAi/seek/target/genQry-0.0.1-SNAPSHOT.jar started by ashoksekar in /Users/ashoksekar/workspace/genAi/seek)
    //2026-03-25T09:55:58.866-05:00 DEBUG 57660 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Running with Spring Boot v3.2.5, Spring v6.1.6
    //2026-03-25T09:55:58.866-05:00  INFO 57660 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : No active profile set, falling back to 1 default profile: "default"
    //2026-03-25T09:55:59.978-05:00  INFO 57660 --- [genQry] [           main] c.nlp.rag.seek.config.JwtTokenProvider   : JwtTokenProvider initialised — expiration=86400000ms
    //2026-03-25T09:55:59.998-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.config.JwtAuthenticationFilter   : Filter 'jwtAuthenticationFilter' configured for use
    //2026-03-25T09:56:00.093-05:00  INFO 57660 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → PRIMARY (genQry) set to read-only mode
    //2026-03-25T09:56:00.096-05:00  INFO 57660 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → PRIMARY (genQry) reachable
    //2026-03-25T09:56:00.416-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.service.SQLExplanationService    : Explanation rules loaded from BusinessRulesForPrompts.json — 321 chars
    //2026-03-25T09:56:00.429-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    : Abbreviations loaded from supportingFiles/abbreviations.json — 152 entries across all sections
    //2026-03-25T09:56:00.430-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'employee' → 8 synonym(s)
    //2026-03-25T09:56:00.430-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'customer' → 5 synonym(s)
    //2026-03-25T09:56:00.430-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'order' → 5 synonym(s)
    //2026-03-25T09:56:00.430-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'product' → 5 synonym(s)
    //2026-03-25T09:56:00.430-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'department' → 5 synonym(s)
    //2026-03-25T09:56:00.430-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'invoice' → 4 synonym(s)
    //2026-03-25T09:56:00.431-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'payment' → 5 synonym(s)
    //2026-03-25T09:56:00.431-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'salary' → 5 synonym(s)
    //2026-03-25T09:56:00.431-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'address' → 4 synonym(s)
    //2026-03-25T09:56:00.431-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'account' → 4 synonym(s)
    //2026-03-25T09:56:00.431-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'vendor' → 4 synonym(s)
    //2026-03-25T09:56:00.431-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'category' → 4 synonym(s)
    //2026-03-25T09:56:00.431-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'transaction' → 4 synonym(s)
    //2026-03-25T09:56:00.431-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'revenue' → 4 synonym(s)
    //2026-03-25T09:56:00.431-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'headcount' → 4 synonym(s)
    //2026-03-25T09:56:00.431-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'recent orders' → 4 synonym(s)
    //2026-03-25T09:56:00.431-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    :   Alias group 'inventory' → 4 synonym(s)
    //2026-03-25T09:56:00.431-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.s.AbbreviationExpanderService    : Semantic alias groups loaded from supportingFiles/semantic.json — 17 groups
    //2026-03-25T09:56:00.434-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.s.MetadataDirectoryResolver      : MetadataDirectoryResolver ready — default dir: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/metadata, supportingFiles: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles
    //2026-03-25T09:56:00.439-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : ═══ RAG pipeline startup ═══
    //2026-03-25T09:56:00.439-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Root schema exists at '/Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/root/root_ecommerce_DBSchema.json' — initialising RAG from file…
    //2026-03-25T09:56:00.439-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.s.MetadataDirectoryResolver      : Latest schema filename registered — database='ecommerce' → file='root_ecommerce_DBSchema.json'
    //2026-03-25T09:56:00.439-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.service.SchemaExtractionService  : Building activeSchema from JSON file for database 'ecommerce'
    //2026-03-25T09:56:00.439-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.service.SchemaFileReaderService  : Using root ecommerce schema file: /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/root/root_ecommerce_DBSchema.json
    //2026-03-25T09:56:00.451-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.service.SchemaFileReaderService  : Loaded schema file: root_ecommerce_DBSchema.json
    //2026-03-25T09:56:00.452-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.service.SchemaExtractionService  : buildActiveSchemaFromJson: 'ecommerce' — 9 table(s) loaded from JSON (business_context preserved for all tables)
    //2026-03-25T09:56:00.453-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'attributes.name' → 'name'
    //2026-03-25T09:56:00.454-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'attributes.unit' → 'unit'
    //2026-03-25T09:56:00.454-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'categories.name' → 'name'
    //2026-03-25T09:56:00.455-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'customers.name' → 'name'
    //2026-03-25T09:56:00.456-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'products.name' → 'name'
    //2026-03-25T09:56:00.456-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'products.sku' → 'sku'
    //2026-03-25T09:56:00.456-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  :   Column enrichment: 'suppliers.name' → 'name'
    //2026-03-25T09:56:00.457-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.service.SchemaEnrichmentService  : Schema enrichment complete — 0 tables enriched, 7 columns enriched (strategy=AUTO)
    //2026-03-25T09:56:00.458-05:00  INFO 57660 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : Table 'attributes' identified as EAV (signals=6) — entityId='attributeid', attrName='name', attrValue='datatype'
    //2026-03-25T09:56:00.458-05:00  INFO 57660 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : Table 'productattributevalues' identified as EAV (signals=4) — entityId='attributeid', attrName='attribute_name', attrValue='value'
    //2026-03-25T09:56:00.458-05:00  INFO 57660 --- [genQry] [           main] c.n.r.seek.service.EavDetectionService   : EAV detection complete — 2 EAV/vertical table(s) identified in schema 'ecommerce'
    //2026-03-25T09:56:00.460-05:00 DEBUG 57660 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : EAV table 'attributes' has no known_attributes catalogued — only TABLE/COLUMN chunks produced
    //2026-03-25T09:56:00.462-05:00 DEBUG 57660 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : EAV table 'productattributevalues' has no known_attributes catalogued — only TABLE/COLUMN chunks produced
    //2026-03-25T09:56:00.463-05:00  INFO 57660 --- [genQry] [           main] c.nlp.rag.seek.service.ChunkingService   : Chunking complete: 76 metadata-enriched chunks from schema 'ecommerce' (chunkSize=80, stride=40, overlap=40)
    //2026-03-25T09:56:00.463-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Ecommerce schema loaded from file: 9 tables, 76 chunks
    //2026-03-25T09:56:00.463-05:00  INFO 57660 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Indexing 76 metadata-enriched chunks for database 'ecommerce'...
    //2026-03-25T09:56:16.248-05:00  INFO 57660 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Indexed 76 chunks (76 with embeddings, 0 keyword-only) into vector store
    //2026-03-25T09:56:16.251-05:00  INFO 57660 --- [genQry] [           main] c.n.rag.seek.service.VectorStoreService  : Semantic search ENABLED — embedding model active
    //2026-03-25T09:56:16.319-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.s.VectorIndexPersistenceService  : Vector index persisted → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/vector_index_ecommerce.json (76 chunks)
    //2026-03-25T09:56:16.319-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : Vector store ready — 76 entries indexed (SEMANTIC mode) user='root'
    //2026-03-25T09:56:16.319-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.s.RAGInitializationService       : ═══ RAG pipeline ready (ecommerce from file) ═══
    //2026-03-25T09:56:16.341-05:00  INFO 57660 --- [genQry] [           main] com.nlp.rag.seek.config.RedisConfig      : Redis connection factory configured → localhost:6379
    //2026-03-25T09:56:16.403-05:00  INFO 57660 --- [genQry] [           main] com.nlp.rag.seek.config.RedisConfig      : RedisTemplate<String, CacheEntry> configured with Jackson JSON serializer
    //2026-03-25T09:56:16.413-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.service.FernetEncryptionService  : Fernet: loaded key from configuration
    //2026-03-25T09:56:16.415-05:00  INFO 57660 --- [genQry] [           main] c.n.r.seek.service.SQLGenerationService  : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=732 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T09:56:16.816-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.doc.service.DocumentRagService   : Doc business rules loaded from supportingFiles/BusinessRulesForPrompts.json — 571 chars
    //2026-03-25T09:56:16.847-05:00  INFO 57660 --- [genQry] [           main] c.nlp.rag.seek.config.DataSourceConfig   : ✅ DataSource → SECONDARY (ecommerce) reachable
    //2026-03-25T09:56:16.891-05:00  WARN 57660 --- [genQry] [           main] .s.s.UserDetailsServiceAutoConfiguration :
    //
    //Using generated security password: 92b356db-2618-4d37-acff-35853a6d4391
    //
    //This generated password is for development use only. Your security configuration must be updated before running your application in production.
    //
    //2026-03-25T09:56:17.273-05:00  INFO 57660 --- [genQry] [           main] com.nlp.rag.seek.SeekApplication         : Started SeekApplication in 18.645 seconds (process running for 18.945)
    //2026-03-25T09:56:17.275-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : Running genQry DB migrations…
    //2026-03-25T09:56:17.333-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : Migration step (skipped): StatementCallback; bad SQL grammar [CREATE INDEX IF NOT EXISTS idx_transcript_session  ON transcript_details(session_id)]
    //2026-03-25T09:56:17.336-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : business_rules table already seeded (35 rows) — skipping
    //2026-03-25T09:56:17.337-05:00 DEBUG 57660 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : DATA_NOT_AVAILABLE rule already exists — skipping
    //2026-03-25T09:56:17.337-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.config.DatabaseMigrationRunner   : genQry DB migrations complete ✅
    //2026-03-25T09:56:17.342-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : BusinessRulesForPrompts.json exported — 35 rules → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/BusinessRulesForPrompts.json
    //2026-03-25T09:56:17.343-05:00  INFO 57660 --- [genQry] [           main] c.n.r.seek.service.SQLGenerationService  : Business rules loaded from supportingFiles/BusinessRulesForPrompts.json — systemInstruction=117 chars, generalRules=732 chars, eavRules=811 chars, retryRules=175 chars
    //2026-03-25T09:56:17.343-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : SQLGenerationService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T09:56:17.343-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.service.SQLExplanationService    : Explanation rules loaded from BusinessRulesForPrompts.json — 321 chars
    //2026-03-25T09:56:17.343-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : SQLExplanationService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T09:56:17.343-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.doc.service.DocumentRagService   : Doc business rules loaded from supportingFiles/BusinessRulesForPrompts.json — 571 chars
    //2026-03-25T09:56:17.343-05:00  INFO 57660 --- [genQry] [           main] c.n.r.s.c.BusinessRulesJsonExporter      : DocumentRagService reloaded rules from BusinessRulesForPrompts.json
    //2026-03-25T09:56:32.336-05:00  INFO 57660 --- [genQry] [nio-9095-exec-1] c.n.rag.seek.controller.AuthController   : POST /api/v1/auth/token — username='AshokSekar'
    //2026-03-25T09:56:32.405-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-1] c.nlp.rag.seek.config.JwtTokenProvider   : JWT generated for email='mailashoky@gmail.com' username='AshokSekar' userType='Root' expires=Thu Mar 26 09:56:32 CDT 2026
    //2026-03-25T09:56:32.407-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-3] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:32.408-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-2] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:32.416-05:00  INFO 57660 --- [genQry] [nio-9095-exec-2] c.n.r.s.controller.WorkspaceController   : GET /api/v1/workSpace — username='AshokSekar' usertype='Root'
    //2026-03-25T09:56:32.417-05:00  INFO 57660 --- [genQry] [nio-9095-exec-3] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T09:56:32.421-05:00  INFO 57660 --- [genQry] [nio-9095-exec-2] c.n.r.s.controller.WorkspaceController   : Workspace: user='AshokSekar' usertype='Root' isGuest=false → fileList=[{name=ecommerce, type=database}, {name=2021.pdf, type=document}, {name=2023.pdf, type=document}, {name=AshokkumarSeka.docx, type=document}, {name=AshokW2.pdf, type=document}]
    //2026-03-25T09:56:32.441-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-3] c.n.r.s.c.QueryHistoryController         : Loaded 65 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T09:56:34.272-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-4] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:34.276-05:00  INFO 57660 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg
    //2026-03-25T09:56:34.276-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T09:56:34.276-05:00  INFO 57660 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T09:56:34.276-05:00  INFO 57660 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T09:56:34.276-05:00  INFO 57660 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg' | db='ecommerce' | topK=3
    //2026-03-25T09:56:34.277-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T09:56:34.277-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-4] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:56:34.376-05:00  INFO 57660 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SemanticCacheService  : ✅ Tier-1 HIT [norm-key] key='genqry:cache:ecommerce:norm:5ac20691dbc25d94' canonical='get 1 heavier kg product than' hits=1
    //2026-03-25T09:56:34.377-05:00  INFO 57660 --- [genQry] [nio-9095-exec-4] c.n.r.seek.service.SQLGenerationService  : ◀ Cache HIT — returning cached response (similarity=1.0000, hits=1)
    //2026-03-25T09:56:34.378-05:00  INFO 57660 --- [genQry] [nio-9095-exec-4] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=true | executed=false
    //2026-03-25T09:56:34.388-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:34.389-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T09:56:34.391-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.s.c.QueryHistoryController         : Loaded 65 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T09:56:37.402-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-7] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:37.402-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-6] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:37.420-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-8] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:39.784-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-9] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:39.789-05:00  INFO 57660 --- [genQry] [nio-9095-exec-9] c.n.rag.seek.service.CacheAdminService   : 🗑  Bulk evict: 2 entries (ALL) deleted
    //2026-03-25T09:56:39.789-05:00  WARN 57660 --- [genQry] [nio-9095-exec-9] c.n.r.s.controller.CacheAdminController  : ⚠️  FULL CACHE FLUSH — 2 entries deleted
    //2026-03-25T09:56:39.800-05:00 DEBUG 57660 --- [genQry] [io-9095-exec-10] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:39.801-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-1] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:39.815-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-2] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:41.648-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-3] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:41.648-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-4] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:41.649-05:00  INFO 57660 --- [genQry] [nio-9095-exec-3] c.n.r.s.controller.WorkspaceController   : GET /api/v1/workSpace — username='AshokSekar' usertype='Root'
    //2026-03-25T09:56:41.649-05:00  INFO 57660 --- [genQry] [nio-9095-exec-4] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T09:56:41.650-05:00  INFO 57660 --- [genQry] [nio-9095-exec-3] c.n.r.s.controller.WorkspaceController   : Workspace: user='AshokSekar' usertype='Root' isGuest=false → fileList=[{name=ecommerce, type=database}, {name=2021.pdf, type=document}, {name=2023.pdf, type=document}, {name=AshokkumarSeka.docx, type=document}, {name=AshokW2.pdf, type=document}]
    //2026-03-25T09:56:41.651-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-4] c.n.r.s.c.QueryHistoryController         : Loaded 65 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json
    //2026-03-25T09:56:44.226-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:44.227-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.controller.NL2SQLController   : Received NL2SQL request: List all the products which is heavier than 1 kg
    //2026-03-25T09:56:44.227-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.ClientSourceDetector  : ClientSource: BODY hint 'UI' → UI
    //2026-03-25T09:56:44.227-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.controller.NL2SQLController   : ClientSource detected: UI
    //2026-03-25T09:56:44.227-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.controller.NL2SQLController   : username=AshokSekar
    //2026-03-25T09:56:44.227-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : ▶ NL→SQL pipeline | query='List all the products which is heavier than 1 kg' | db='ecommerce' | topK=3
    //2026-03-25T09:56:44.228-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.PiiSanitizationService   : PII scan: no PII/PHI detected in query
    //2026-03-25T09:56:44.228-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:56:44.229-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:56:44.229-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SemanticCacheService  : Tier-1 MISS key='genqry:cache:ecommerce:norm:5ac20691dbc25d94'  canonical='get 1 heavier kg product than'
    //2026-03-25T09:56:44.405-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SemanticCacheService  : Tier-2 MISS — no vector entries for db='ecommerce'
    //2026-03-25T09:56:44.565-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.VectorStoreService  : Semantic search 'List all the products which is heavier than 1 kg' → top 5 results:
    //2026-03-25T09:56:44.566-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2993468860954682 | table=orderdetails#0 col=orderdetails
    //2026-03-25T09:56:44.566-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2968980626710963 | table=products#0 col=products
    //2026-03-25T09:56:44.566-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.24062708087498832 | table=productsuppliers.productid#0 col=productsuppliers
    //2026-03-25T09:56:44.566-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.2398932629731892 | table=suppliers#0 col=suppliers
    //2026-03-25T09:56:44.566-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.VectorStoreService  :   [score={:.3f}] 0.23089867572205303 | table=orderdetails.productid#0 col=orderdetails
    //2026-03-25T09:56:44.566-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : Adaptive topK: including extra chunk 'suppliers#0' [score=0.240] (above threshold 0.15)
    //2026-03-25T09:56:44.566-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : Adaptive topK: including extra chunk 'orderdetails.productid#0' [score=0.231] (above threshold 0.15)
    //2026-03-25T09:56:44.566-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : Adaptive topK expanded: 3 → 5 chunks (threshold=0.15)
    //2026-03-25T09:56:44.568-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : EAV-injection: force-injected EAV table 'attributes' for query terms [weight]
    //2026-03-25T09:56:44.568-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : EAV-injection: force-injected EAV table 'productattributevalues' for query terms [weight]
    //2026-03-25T09:56:44.569-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : FK-expansion: injected table 'orders' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers, suppliers, attributes, productattributevalues])
    //2026-03-25T09:56:44.569-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : FK-expansion: injected table 'categories' (FK referenced by retrieved tables: [orderdetails, products, productsuppliers, suppliers, attributes, productattributevalues])
    //2026-03-25T09:56:44.569-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : TopK cap: 9 chunks → 3 semantic + 4 injected(FK/EAV/query-term) = 7 total (topK=3)
    //2026-03-25T09:56:44.570-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping orderdetails#0 [score=0.299] for table 'orderdetails'
    //2026-03-25T09:56:44.570-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping products#0 [score=0.297] for table 'products'
    //2026-03-25T09:56:44.570-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping productsuppliers.productid#0 [score=0.241] for table 'productsuppliers'
    //2026-03-25T09:56:44.570-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping attributes#0 [score=0.500] for table 'attributes'
    //2026-03-25T09:56:44.570-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping productattributevalues#0 [score=0.500] for table 'productattributevalues'
    //2026-03-25T09:56:44.570-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping orders#0 [score=0.000] for table 'orders'
    //2026-03-25T09:56:44.570-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : Dedup: Keeping categories#0 [score=0.000] for table 'categories'
    //2026-03-25T09:56:44.570-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : Sending sanitized query to LLM with top-7 schema chunks
    //2026-03-25T09:56:44.573-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : ══════════════ PROMPT SENT TO LLM ══════════════
    //You are an expert SQL developer. Convert the natural-language question into a single, correct, executable SQL query..
    //
    //Rules:
    //1. Use ONLY the tables and columns that appear in the schema context below.
    //2. Write standard SQL compatible with PostgreSQL.
    //3. Use explicit JOINs with ON clauses — never implicit comma joins.
    //4. Add a WHERE clause whenever a filter is implied.
    //5. Use aggregate functions (COUNT, SUM, AVG, MAX, MIN) when appropriate.
    //6. Add ORDER BY when a ranking or 'top N' is implied.
    //7. Return ONLY the SQL statement — no prose, no markdown fences.
    //8. CRITICAL JOIN RULE — if two tables doesnt have foreign key relationship then dont join them
    //10. MPORTANT: ALL tables and columns listed in the schema context ARE valid and exist in the database — you MUST use them if they match the user's question
    //11. End the sql statement with a semicolon.
    //
    //Follow the below rules for EAV tables:
    //1. NEVER use attribute names (e.g. Weight, Color, Size) as SQL column names.
    //2. Use WHERE <attr_name_col> = '<key>' to select a specific attribute.
    //3. Use CAST(<attr_value_col> AS <type>) when reading numeric or date values.
    //4. For multiple attributes in one row, use conditional aggregation: MAX(CASE WHEN <attr_name_col> = '<key1>' THEN CAST(<attr_value_col> AS <type>) END) AS <key1>
    //5. Join to the entity table via the entity_id column shown in the context.
    //6. EAV attributes retrieved for this query are listed in the schema context — use their FILTER and CAST HINT values exactly.
    //7. Attribute names are stored as ROW VALUES in the attribute name column.
    //8. Join the entity table to the EAV data table on the entity ID column.
    //9. Join the EAV data table to the attributes table on the attribute ID column.
    //
    //Database: ecommerce
    //
    //--- RETRIEVED SCHEMA CHUNKS (ranked by semantic similarity) ---
    //• orderdetails#0 [score=0.299]
    //  TABLE   : orderdetails
    //  COLUMNS : orderdetailid:serial, orderid:integer, productid:integer, quantity:integer, unitprice:numeric, linetotal:numeric
    //  PKs     : orderdetailid
    //  FKs     : orderid->orders.orderid, productid->products.productid
    //  CONTEXT : Line items for each order. Each row represents one product in an order.
    //  DESC    : Line items for each order. Each row represents one product in an order.
    //  TEXT    : Table: orderdetails. Description: Line items for each order. Each row represents one product in an order.. Columns: orderdetailid (serial) [PK]; orderid (integer) [FK -> orders.orderid]; productid (integer) [FK -> products.productid]; quantity (integer); unitprice (numeric); linetotal (numeric); Column descriptions: orderdetailid: orderdetailid (serial). orderid: orderid (integer). productid: productid (integer). quantity: quantity (integer). unitprice: unitprice (numeric). linetotal: linetotal (numeric).
    //
    //• products#0 [score=0.297]
    //  TABLE   : products
    //  COLUMNS : productid:serial, name:character varying, description:text, categoryid:integer, price:numeric, stockqty:integer, sku:character varying, isactive:boolean, createdat:timestamp, updatedat:timestamp
    //  PKs     : productid
    //  FKs     : categoryid->categories.categoryid
    //  CONTEXT : Master product catalogue. Each product belongs to one category and may have many EAV attributes.
    //  DESC    : Master product catalogue. Each product belongs to one category and may have many EAV attributes.
    //  TEXT    : Table: products. Description: Master product catalogue. Each product belongs to one category and may have many EAV attributes.. Columns: productid (serial) [PK]; name (character varying); description (text); categoryid (integer) [FK -> categories.categoryid]; price (numeric); stockqty (integer); sku (character varying); isactive (boolean); createdat (timestamp); updatedat (timestamp); Column descriptions: productid: productid (serial). name: Name — the name of the products. Original column name: name.. description: description (text). categoryid: categoryid (integer). price: price (numeric). stockqty: stockqty (integer). sku: Sku — the sku of
    //
    //• productsuppliers.productid#0 [score=0.241]
    //  TABLE   : productsuppliers
    //  COLUMN  : productid
    //  TYPE    : integer
    //  ROLE    : PRIMARY KEY
    //  FK REF  : products.productid
    //  DESC    : productid (integer)
    //  TEXT    : Column: productid. Data type: integer. Description: productid (integer). Role: primary key — uniquely identifies each row. Role: foreign key referencing products.productid. Nullable: no.
    //
    //• attributes#0 [score=0.500]
    //  TABLE         : attributes
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : name
    //  ATTR VALUE COL: datatype
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.
    //  TEXT          : Table: attributes. Description: EAV meta table — defines the set of dynamic attribute names (e.g. Color, Size, Weight) that can be attached to any product.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE name = '<attribute_key>' to filter, and read datatype for the value. Entity linked via attributeid. Columns: attributeid (serial) [PK]; name (character varying); datatype (character varying); unit (character varying); description (text); createdat (timestamp); Column descriptions: attributeid:
    //
    //• productattributevalues#0 [score=0.500]
    //  TABLE         : productattributevalues
    //  ⚠ EAV TABLE   : This table uses Entity-Attribute-Value storage.
    //  ATTR NAME COL : attribute_name
    //  ATTR VALUE COL: value
    //  ENTITY ID COL : attributeid
    //  RULE          : Use WHERE attribute_name = '<key>' — NEVER reference attribute names as column names.
    //  DESC          : EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.
    //  TEXT          : Table: productattributevalues. Description: EAV data table — stores dynamic attribute values for each product. Each row represents one attribute value for one product. Query pattern: WHERE AttributeID = <id> to filter by attribute; use CAST(Value AS <type>) or the typed ValueInt/ValueDecimal/ValueBool/ValueDate columns for range queries.. WARNING: This is an EAV (Entity-Attribute-Value) table. Do NOT use attribute names as column names in SQL. Access pattern: WHERE attribute_name = '<attribute_key>' to filter, and read value for the value. Entity linked via attributeid
    //
    //• orders#0 [score=0.000]
    //  TABLE   : orders
    //  COLUMNS : orderid:serial, customerid:integer, orderdate:date, totalamount:numeric, status:character varying, createdat:timestamp, updatedat:timestamp
    //  PKs     : orderid
    //  FKs     : customerid->customers.customerid
    //  CONTEXT : Records each customer order. TotalAmount is the sum of all OrderDetails lines.
    //  DESC    : Records each customer order. TotalAmount is the sum of all OrderDetails lines.
    //  TEXT    : Table: orders. Description: Records each customer order. TotalAmount is the sum of all OrderDetails lines.. Columns: orderid (serial) [PK]; customerid (integer) [FK -> customers.customerid]; orderdate (date); totalamount (numeric); status (character varying); createdat (timestamp); updatedat (timestamp); Column descriptions: orderid: orderid (serial). customerid: customerid (integer). orderdate: orderdate (date). totalamount: totalamount (numeric). status: status (character varying). createdat: createdat (timestamp). updatedat: updatedat (timestamp).
    //
    //• categories#0 [score=0.000]
    //  TABLE   : categories
    //  COLUMNS : categoryid:serial, name:character varying, description:text, createdat:timestamp
    //  PKs     : categoryid
    //  CONTEXT : Product categories used to group and filter products.
    //  DESC    : Product categories used to group and filter products.
    //  TEXT    : Table: categories. Description: Product categories used to group and filter products.. Columns: categoryid (serial) [PK]; name (character varying); description (text); createdat (timestamp); Column descriptions: categoryid: categoryid (serial). name: Name — the name of the categories. Original column name: name.. description: description (text). createdat: createdat (timestamp).
    //--- END OF SCHEMA CHUNKS ---
    //
    //Natural-language question: List all the products which is heavier than 1 kg
    //
    //SQL:
    //
    //══════════════ END OF PROMPT ══════════════
    //2026-03-25T09:56:46.777-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : ══════════════ LLM RESPONSE ══════════════
    //```sql
    //SELECT p.productid, p.name, p.description, p.price, p.stockqty, p.sku
    //FROM products p
    //JOIN productattributevalues pav ON p.productid = pav.attributeid
    //JOIN attributes a ON pav.attributeid = a.attributeid
    //WHERE a.name = 'Weight' AND CAST(pav.value AS numeric) > 1;
    //```
    //══════════════ END OF LLM RESPONSE ══════════════
    //2026-03-25T09:56:46.778-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : ◀ LLM generated SQL:
    //══════════════════════════════
    //SELECT p.productid, p.name, p.description, p.price, p.stockqty, p.sku
    //FROM products p
    //JOIN productattributevalues pav ON p.productid = pav.attributeid
    //JOIN attributes a ON pav.attributeid = a.attributeid
    //WHERE a.name = 'Weight' AND CAST(pav.value AS numeric) > 1;
    //══════════════════════════════
    //2026-03-25T09:56:46.779-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLValidationService  : SQL validation: valid=true, errors=[], warnings=[EAV table 'productattributevalues' is referenced, but the attribute name column 'attribute_name' does not appear in the SQL. Did the LLM use an attribute key as a column name? Expected pattern: WHERE attribute_name = '<key>']
    //2026-03-25T09:56:46.782-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.ConfidenceScoreService   : Confidence: semantic=0.262(×0.40) | coverage=0.286(×0.20) | validity=1.0(×0.20) | tableMatch=0.429(×0.20) | → total=0.448 [LOW]
    //2026-03-25T09:56:46.782-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION PROMPT SENT TO LLM ══════════════
    //1. You are a helpful data analyst. Given the SQL query below, write a concise,
    //plain-English explanation (2–4 sentences) describing:
    //2. Which data is being retrieved or modified.
    //3. What filters or conditions apply.
    //4. How the results are ordered or grouped (if any).
    //5. The business purpose this query is likely serving.
    //
    //Do NOT include the SQL in your answer. Use simple language a non-technical
    //business user would understand.
    //
    //SQL:
    //SELECT p.productid, p.name, p.description, p.price, p.stockqty, p.sku
    //FROM products p
    //JOIN productattributevalues pav ON p.productid = pav.attributeid
    //JOIN attributes a ON pav.attributeid = a.attributeid
    //WHERE a.name = 'Weight' AND CAST(pav.value AS numeric) > 1;
    //══════════════ END OF EXPLANATION PROMPT ══════════════
    //2026-03-25T09:56:50.004-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.SQLExplanationService    : ══════════════ EXPLANATION LLM RESPONSE ══════════════
    //This query retrieves information about products, including their ID, name, description, price, stock quantity, and SKU. It specifically looks for products that have a weight attribute greater than 1. The data is filtered to only include products with this weight condition. The likely business purpose of this query is to identify and analyze products that are heavier, which could be useful for inventory management, shipping considerations, or marketing strategies.
    //══════════════ END OF EXPLANATION LLM RESPONSE ══════════════
    //2026-03-25T09:56:50.004-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SQLGenerationService  : ◀ NL→SQL pipeline done | valid=true | confidence=0.448 [LOW] | piiTokens=0 | structuralRetry=false
    //2026-03-25T09:56:50.011-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.TranscriptService   : Transcript appended → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts_03_25_26.json (11 entries total)
    //2026-03-25T09:56:50.013-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.TranscriptService   : request userName='AshokSekar' resolved to userId=1000
    //2026-03-25T09:56:50.020-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.repository.UserRepository   : Transcript inserted | id=1096 | userId=1000 | status=SUCCESS | dataSource=ecommerce
    //2026-03-25T09:56:50.020-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.rag.seek.service.TranscriptService   : Transcript persisted to DB with id=1096
    //2026-03-25T09:56:50.032-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] com.nlp.rag.seek.service.UserDbService   : transcript_json JDBC type=org.postgresql.util.PGobject value-preview='{"remarks": "", "userPrompt": "List all the products which is heavier than 1 kg", "explanation": "This query retrieves i'
    //2026-03-25T09:56:50.041-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] com.nlp.rag.seek.service.UserDbService   : Exported 66 transcripts → /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json for user='AshokSekar'
    //2026-03-25T09:56:50.041-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:56:50.222-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-5] c.n.r.s.service.QueryNormalizerService   : normalize: 'List all the products which is heavier than 1 kg' → 'get 1 heavier kg product than'
    //2026-03-25T09:56:50.224-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-1/norm] key='genqry:cache:ecommerce:norm:5ac20691dbc25d94' canonical='get 1 heavier kg product than' query='List all the products which is heavier than 1 kg'
    //2026-03-25T09:56:50.226-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.service.SemanticCacheService  : 📥 Cache STORE [tier-2/vec] key='genqry:cache:ecommerce:vec:cacadf2d-6773-43b6-85d2-3d9b35c5c218' query='List all the products which is heavier than 1 kg'
    //2026-03-25T09:56:50.226-05:00  INFO 57660 --- [genQry] [nio-9095-exec-5] c.n.r.seek.controller.NL2SQLController   : NL2SQL complete | source=UI | cacheHit=false | executed=false
    //2026-03-25T09:56:50.235-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-7] c.n.r.s.config.JwtAuthenticationFilter   : JWT authenticated: email='mailashoky@gmail.com' username='AshokSekar' userType='Root'
    //2026-03-25T09:56:50.236-05:00  INFO 57660 --- [genQry] [nio-9095-exec-7] c.n.r.s.c.QueryHistoryController         : GET /api/v1/query-history — userName='AshokSekar' page=0 size=10
    //2026-03-25T09:56:50.237-05:00 DEBUG 57660 --- [genQry] [nio-9095-exec-7] c.n.r.s.c.QueryHistoryController         : Loaded 66 transcript entries from /Users/ashoksekar/workspace/genAi/seek/src/main/resources/supportingFiles/AshokSekar/transcripts.json────────────────────────────────────

    /**
     * POST /api/v1/nl2sql
     *
     * Converts a natural language query to SQL.
     * The response always contains both:
     *   generatedSQL  — raw SQL (unchanged, for programmatic use)
     *   formattedSQL  — UI-formatted if caller is a browser, compact if API
     *   clientSource  — detected source ("UI" | "API" | "CURL" | "BATCH")
     */
    @Operation(summary = "Convert natural language to SQL", description = "Takes a natural language question and returns generated SQL with confidence scoring and optional execution results")
    @PostMapping("/nl2sql")
    public ResponseEntity<SQLGenerationResponse> convertNaturalLanguageToSQL(
            @RequestBody NaturalLanguageQueryRequest request,
            HttpServletRequest httpRequest) {

        logger.info("Received NL2SQL request: {}", request.getQuery());
        if (request.getQuery() == null || request.getQuery().trim().isEmpty())
            return ResponseEntity.badRequest().build();

        // ── Detect client source ───────────────────────────────────────────────
        Source source = clientSourceDetector.detect(httpRequest, request.getSource());
        logger.info("ClientSource detected: {}", source);

        String databaseName = request.getDatabaseName() != null
                ? request.getDatabaseName() : "ecommerce_db";
        int topK = request.getTopK() > 0 ? request.getTopK() : 5;

        try {
            // Pass the full request so all fields are captured in the transcript
            logger.info("username={} ",request.getUserName());

            SQLGenerationResponse response = sqlGenerationService.generateSQL(request);

            // ── Format SQL based on detected source ────────────────────────────
            applyFormatting(response, source);

            // ── Optionally execute the validated SQL and attach results ─────────
            if (request.isShowSampleResults() && response.isSqlValid()
                    && response.getGeneratedSQL() != null
                    && response.getError() == null) {

                int sampleLimit = (request.getSampleLimit() > 0
                        && request.getSampleLimit() <= 200)
                        ? request.getSampleLimit() : 50;

                logger.info("showSampleResults=true | executing SQL | limit={} | db='{}'",
                        sampleLimit, databaseName);
                SQLExecutionResult execResult = sqlExecutionService.execute(
                        response.getGeneratedSQL(), sampleLimit, databaseName);
                response.setExecutionResult(execResult);
                logger.info("SQL execution done | success={} | rows={} | truncated={}",
                        execResult.isSuccess(), execResult.getRowCount(), execResult.isTruncated());
            }

            logger.info("NL2SQL complete | source={} | cacheHit={} | executed={}",
                    source, response.isCacheHit(), request.isShowSampleResults());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error processing NL2SQL request", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ── PII Check ─────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/pii-check
     *
     * Standalone PII inspection — no SQL generation, no LLM call.
     * Returns the sanitized query and a safe summary of detected PII tokens.
     */
    @Operation(summary = "PII inspection", description = "Scans query for PII tokens and returns sanitized version — no SQL generation")
    @PostMapping("/pii-check")
    public ResponseEntity<Map<String, Object>> checkPii(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        if (query == null || query.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "query field is required"));

        PiiSanitizationResult result = piiSanitizationService.sanitize(query);
        List<Map<String, String>> tokenSummary = new ArrayList<>();
        for (PiiToken t : result.getTokens()) {
            tokenSummary.add(Map.of(
                "category",    t.getCategory(),
                "placeholder", t.getPlaceholder(),
                "maskedValue", maskForResponse(t.getOriginalValue()),
                "position",    t.getStartIndex() + "-" + t.getEndIndex()
            ));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("originalQuery",  query);
        response.put("sanitizedQuery", result.getSanitizedQuery());
        response.put("piiDetected",    result.isPiiDetected());
        response.put("tokenCount",     result.getTokens().size());
        response.put("tokens",         tokenSummary);
        return ResponseEntity.ok(response);
    }

    // ── Feedback ──────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/feedback
     *
     * Updates feedback_type and user_feedback in the transcript_details row
     * identified by transcriptId.
     *
     * Request body:
     * {
     *   "transcriptId" : 35,
     *   "feedbackType" : "positive",
     *   "feedbackText" : "LLM Generated sql is accurate"
     * }
     *
     * Response 200 : { "status": "ok", "transcriptId": 35, "feedbackType": "POSITIVE" }
     * Response 400 : missing / invalid transcriptId
     * Response 404 : no transcript row found for the given transcriptId
     * Response 503 : DB unavailable
     */
    @Operation(summary = "Submit query feedback", description = "Updates feedback_type and user_feedback for a transcript entry")
    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> submitFeedback(
            @RequestBody Map<String, Object> body) {

        // ── validate transcriptId ─────────────────────────────────────────────
        Object rawId = body.get("transcriptId");
        if (rawId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "transcriptId is required"));
        }
        int transcriptId;
        try {
            transcriptId = ((Number) rawId).intValue();
        } catch (ClassCastException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "transcriptId must be a number"));
        }
        if (transcriptId <= 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "transcriptId is invalid — transcript was not persisted"));
        }

        // ── extract feedback fields (feedbackText → user_feedback, feedbackType → feedback_type) ──
        String userFeedback = body.get("feedbackText") != null
                ? body.get("feedbackText").toString().trim() : null;
        String feedbackType = body.get("feedbackType") != null
                ? body.get("feedbackType").toString().trim().toUpperCase() : null;

        if (userDbService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Database service unavailable"));
        }

        // ── update transcript_details ─────────────────────────────────────────
        int result = userDbService.saveUserFeedback(transcriptId, userFeedback, feedbackType);
        if (result == -1) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No transcript found for transcriptId=" + transcriptId));
        }

        logger.info("Feedback saved | transcriptId={} | feedbackType={}", transcriptId, feedbackType);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status",       "ok");
        resp.put("transcriptId", transcriptId);
        resp.put("feedbackType", feedbackType);
        return ResponseEntity.ok(resp);
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("NL2SQL RAG Pipeline is running");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Stamps clientSource and formattedSQL onto the response.
     * Always sets both fields regardless of whether it is a cache hit or fresh.
     */
    private void applyFormatting(SQLGenerationResponse response, Source source) {
        response.setClientSource(source.name());
        if (response.getGeneratedSQL() != null) {
            response.setFormattedSQL(
                    sqlFormatterService.format(response.getGeneratedSQL(), source));
        }
    }

    /** Partially masks PII value for safe API response — first + last char only */
    private String maskForResponse(String v) {
        if (v == null || v.length() <= 2) return "***";
        return v.charAt(0) + "*".repeat(Math.min(v.length() - 2, 8)) + v.charAt(v.length() - 1);
    }
}
