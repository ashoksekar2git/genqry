package com.nlp.rag.seek;

import com.nlp.rag.seek.service.QueryNormalizerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QueryNormalizerService.
 *
 * Key invariant: semantically equivalent queries MUST produce the
 * same canonical string and therefore the SAME Redis cache key.
 */
class QueryNormalizerServiceTest {

    private QueryNormalizerService normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new QueryNormalizerService();
    }

    // =========================================================================
    // Core requirement: list / show / display / find → same key
    // =========================================================================

    @Test
    @DisplayName("'list', 'show', 'display', 'find', 'get' active employees → same canonical form")
    void activeEmployees_allVariants_sameCanonical() {
        String expected = normalizer.normalize("list all active employees");

        assertEquals(expected, normalizer.normalize("show all active employees"),
                "show == list");
        assertEquals(expected, normalizer.normalize("display the active staff"),
                "display + staff synonym == list + employees");
        assertEquals(expected, normalizer.normalize("find active employees"),
                "find == list");
        assertEquals(expected, normalizer.normalize("get active employees"),
                "get == list (already canonical)");
        assertEquals(expected, normalizer.normalize("Show me ALL Active Employees!"),
                "mixed case + punctuation == list");
        assertEquals(expected, normalizer.normalize("retrieve all active employees"),
                "retrieve == list");
        assertEquals(expected, normalizer.normalize("fetch active employees"),
                "fetch == list");

        // canonical form should be "get active employee"
        assertEquals("get active employee", expected,
                "canonical form must be 'get active employee'");
    }

    // =========================================================================
    // Same Redis key via toCacheKey()
    // =========================================================================

    @Test
    @DisplayName("list and show produce the exact same Redis cache key")
    void listAndShow_sameRedisKey() {
        String key1 = normalizer.toCacheKey("seek:cache:", "ecommerce",
                "list all active employees");
        String key2 = normalizer.toCacheKey("seek:cache:", "ecommerce",
                "show all active employees");
        String key3 = normalizer.toCacheKey("seek:cache:", "ecommerce",
                "display the active staff");
        String key4 = normalizer.toCacheKey("seek:cache:", "ecommerce",
                "find active employees");

        assertEquals(key1, key2, "list and show must map to same Redis key");
        assertEquals(key1, key3, "list and display-staff must map to same Redis key");
        assertEquals(key1, key4, "list and find must map to same Redis key");

        // Key format: seek:cache:<db>:norm:<16-char-hex>
        assertTrue(key1.startsWith("seek:cache:ecommerce:norm:"),
                "Key must have norm: prefix");
        assertEquals(16, key1.substring("seek:cache:ecommerce:norm:".length()).length(),
                "Hash must be 16 hex chars");
    }

    // =========================================================================
    // Customer synonyms
    // =========================================================================

    @Test
    @DisplayName("list/show/display customers/clients/buyers → same canonical form")
    void customerSynonyms_sameCanonical() {
        String expected = normalizer.normalize("list customers");
        assertEquals(expected, normalizer.normalize("show all clients"),
                "clients == customers");
        assertEquals(expected, normalizer.normalize("display the buyers"),
                "buyers == customers");
        assertEquals(expected, normalizer.normalize("get all customers"),
                "get == list");

        assertEquals("get customer", expected,
                "canonical form must be 'get customer'");
    }

    // =========================================================================
    // Order synonyms
    // =========================================================================

    @Test
    @DisplayName("list/show orders/purchases/transactions → same canonical form")
    void orderSynonyms_sameCanonical() {
        String listOrders   = normalizer.normalize("list orders");
        String showPurchases = normalizer.normalize("show all purchases");
        String getTrans     = normalizer.normalize("get transactions");

        assertEquals(listOrders, showPurchases, "purchases == orders");
        assertEquals(listOrders, getTrans,      "transactions == orders");
    }

    // =========================================================================
    // Parameterized: individual synonym pairs
    // =========================================================================

    @ParameterizedTest(name = "''{0}'' and ''{1}'' → same canonical")
    @CsvSource({
        "list employees,       show employees",
        "list employees,       display employees",
        "list employees,       find employees",
        "list employees,       retrieve employees",
        "list employees,       fetch employees",
        "list employees,       view employees",
        "list employees,       search employees",
        "list customers,       show clients",
        "list customers,       display buyers",
        "list staff,           show employees",
        "get workers,          show personnel"
    })
    void synonymPairs_sameCanonical(String q1, String q2) {
        assertEquals(normalizer.normalize(q1.trim()),
                     normalizer.normalize(q2.trim()),
                     q1.trim() + " vs " + q2.trim());
    }

    // =========================================================================
    // Filler-word independence: different filler combos → same canonical
    // =========================================================================

    @Test
    @DisplayName("Filler words do not affect canonical form")
    void fillerWords_ignored() {
        String base = normalizer.normalize("show employees");
        assertEquals(base, normalizer.normalize("show all employees"));
        assertEquals(base, normalizer.normalize("show me the employees"));
        assertEquals(base, normalizer.normalize("can you show me all the employees"));
        assertEquals(base, normalizer.normalize("please show all my employees"));
    }

    // =========================================================================
    // Case and punctuation independence
    // =========================================================================

    @Test
    @DisplayName("Case and punctuation do not affect canonical form")
    void caseAndPunctuation_ignored() {
        String lower = normalizer.normalize("list active employees");
        assertEquals(lower, normalizer.normalize("LIST ACTIVE EMPLOYEES"));
        assertEquals(lower, normalizer.normalize("List Active Employees!"));
        assertEquals(lower, normalizer.normalize("list   active   employees"));
        assertEquals(lower, normalizer.normalize("list-active-employees"));
    }

    // =========================================================================
    // Different intent → DIFFERENT canonical (must NOT collide)
    // =========================================================================

    @Test
    @DisplayName("Different intents produce different canonical forms")
    void differentIntents_differentCanonical() {
        String employees  = normalizer.normalize("show active employees");
        String customers  = normalizer.normalize("show active customers");
        String inactive   = normalizer.normalize("show inactive employees");

        assertNotEquals(employees, customers,
                "employees vs customers must be different keys");
        assertNotEquals(employees, inactive,
                "active vs inactive must be different keys");
    }

    // =========================================================================
    // toCacheKey: DB scoping
    // =========================================================================

    @Test
    @DisplayName("Same query with different database names produces different Redis keys")
    void differentDatabase_differentKey() {
        String key1 = normalizer.toCacheKey("seek:cache:", "ecommerce", "list active employees");
        String key2 = normalizer.toCacheKey("seek:cache:", "production", "list active employees");
        assertNotEquals(key1, key2, "Different DB names must produce different Redis keys");
        assertTrue(key1.contains("ecommerce"),   "Key must contain db name");
        assertTrue(key2.contains("production"),"Key must contain db name");
    }

    // =========================================================================
    // Canonical form is stable (deterministic)
    // =========================================================================

    @Test
    @DisplayName("normalize() is deterministic — same input always yields same output")
    void normalize_isDeterministic() {
        String q = "list all active employees";
        String first = normalizer.normalize(q);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, normalizer.normalize(q),
                    "normalize() must be deterministic");
        }
    }
}

