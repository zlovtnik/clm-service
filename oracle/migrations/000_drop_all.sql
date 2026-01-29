-- =============================================================================
-- MIGRATION 000: NUCLEAR DROP ALL OBJECTS
-- Purpose: Complete clean slate - drops ALL user schema objects
-- Author: GprintEx Team
-- Date: 2026-01-28
-- WARNING: This script DROPS ALL DATA! Use with extreme caution.
-- =============================================================================

SET SERVEROUTPUT ON SIZE UNLIMITED;

PROMPT =====================================================
PROMPT Starting NUCLEAR schema cleanup...
PROMPT This will drop ALL objects in the current schema!
PROMPT =====================================================

-- =============================================================================
-- STEP 1: DROP ALL TRIGGERS FIRST (they depend on tables/views)
-- =============================================================================
BEGIN
    FOR tr IN (SELECT trigger_name FROM user_triggers) LOOP
        DECLARE
            v_quoted_name VARCHAR2(130);
        BEGIN
            v_quoted_name := DBMS_ASSERT.ENQUOTE_NAME(tr.trigger_name, FALSE);
            EXECUTE IMMEDIATE 'DROP TRIGGER ' || v_quoted_name;
            DBMS_OUTPUT.PUT_LINE('Dropped trigger: ' || tr.trigger_name);
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('Error dropping trigger ' || tr.trigger_name || ': ' || SQLERRM);
        END;
    END LOOP;
END;
/

-- =============================================================================
-- STEP 2: DROP ALL PACKAGES (they may depend on types)
-- =============================================================================
BEGIN
    FOR pkg IN (SELECT object_name FROM user_objects WHERE object_type = 'PACKAGE') LOOP
        DECLARE
            v_quoted_name VARCHAR2(130);
        BEGIN
            v_quoted_name := DBMS_ASSERT.ENQUOTE_NAME(pkg.object_name, FALSE);
            EXECUTE IMMEDIATE 'DROP PACKAGE ' || v_quoted_name;
            DBMS_OUTPUT.PUT_LINE('Dropped package: ' || pkg.object_name);
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('Error dropping package ' || pkg.object_name || ': ' || SQLERRM);
        END;
    END LOOP;
END;
/

-- =============================================================================
-- STEP 3: DROP ALL MATERIALIZED VIEWS
-- =============================================================================
BEGIN
    FOR mv IN (SELECT mview_name FROM user_mviews) LOOP
        DECLARE
            v_quoted_name VARCHAR2(130);
        BEGIN
            v_quoted_name := DBMS_ASSERT.ENQUOTE_NAME(mv.mview_name, FALSE);
            EXECUTE IMMEDIATE 'DROP MATERIALIZED VIEW ' || v_quoted_name;
            DBMS_OUTPUT.PUT_LINE('Dropped materialized view: ' || mv.mview_name);
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('Error dropping mview ' || mv.mview_name || ': ' || SQLERRM);
        END;
    END LOOP;
END;
/

-- =============================================================================
-- STEP 4: DROP ALL VIEWS
-- =============================================================================
BEGIN
    FOR v IN (SELECT view_name FROM user_views) LOOP
        DECLARE
            v_quoted_name VARCHAR2(130);
        BEGIN
            v_quoted_name := DBMS_ASSERT.ENQUOTE_NAME(v.view_name, FALSE);
            EXECUTE IMMEDIATE 'DROP VIEW ' || v_quoted_name;
            DBMS_OUTPUT.PUT_LINE('Dropped view: ' || v.view_name);
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('Error dropping view ' || v.view_name || ': ' || SQLERRM);
        END;
    END LOOP;
END;
/

-- =============================================================================
-- STEP 5: DROP ALL FOREIGN KEY CONSTRAINTS
-- =============================================================================
BEGIN
    FOR c IN (SELECT table_name, constraint_name 
              FROM user_constraints 
              WHERE constraint_type = 'R') LOOP
        DECLARE
            v_quoted_table VARCHAR2(130);
            v_quoted_constraint VARCHAR2(130);
        BEGIN
            v_quoted_table := DBMS_ASSERT.ENQUOTE_NAME(c.table_name, FALSE);
            v_quoted_constraint := DBMS_ASSERT.ENQUOTE_NAME(c.constraint_name, FALSE);
            EXECUTE IMMEDIATE 'ALTER TABLE ' || v_quoted_table || 
                              ' DROP CONSTRAINT ' || v_quoted_constraint;
            DBMS_OUTPUT.PUT_LINE('Dropped FK: ' || c.constraint_name);
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('Error dropping FK ' || c.constraint_name || ': ' || SQLERRM);
        END;
    END LOOP;
END;
/

-- =============================================================================
-- STEP 6: DROP ALL TABLES
-- =============================================================================
BEGIN
    FOR t IN (SELECT table_name FROM user_tables) LOOP
        DECLARE
            v_quoted_name VARCHAR2(130);
        BEGIN
            v_quoted_name := DBMS_ASSERT.ENQUOTE_NAME(t.table_name, FALSE);
            EXECUTE IMMEDIATE 'DROP TABLE ' || v_quoted_name || ' CASCADE CONSTRAINTS PURGE';
            DBMS_OUTPUT.PUT_LINE('Dropped table: ' || t.table_name);
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('Error dropping table ' || t.table_name || ': ' || SQLERRM);
        END;
    END LOOP;
END;
/

-- =============================================================================
-- STEP 7: DROP ALL SEQUENCES
-- =============================================================================
BEGIN
    FOR s IN (SELECT sequence_name FROM user_sequences) LOOP
        DECLARE
            v_quoted_name VARCHAR2(130);
        BEGIN
            v_quoted_name := DBMS_ASSERT.ENQUOTE_NAME(s.sequence_name, FALSE);
            EXECUTE IMMEDIATE 'DROP SEQUENCE ' || v_quoted_name;
            DBMS_OUTPUT.PUT_LINE('Dropped sequence: ' || s.sequence_name);
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('Error dropping sequence ' || s.sequence_name || ': ' || SQLERRM);
        END;
    END LOOP;
END;
/

-- =============================================================================
-- STEP 8: DROP ALL TYPES (with FORCE to handle dependencies)
-- Run multiple times because of type dependencies
-- =============================================================================
DECLARE
    v_remaining NUMBER := 1;
    v_iterations NUMBER := 0;
    v_dropped NUMBER;
    v_quoted_name VARCHAR2(130);
BEGIN
    -- Count initial remaining types
    SELECT COUNT(*) INTO v_remaining 
    FROM user_types WHERE typecode IN ('OBJECT', 'COLLECTION');
    
    WHILE v_remaining > 0 AND v_iterations < 10 LOOP
        v_iterations := v_iterations + 1;
        v_dropped := 0;
        
        FOR t IN (SELECT type_name FROM user_types WHERE typecode IN ('OBJECT', 'COLLECTION')) LOOP
            BEGIN
                v_quoted_name := DBMS_ASSERT.ENQUOTE_NAME(t.type_name, FALSE);
                EXECUTE IMMEDIATE 'DROP TYPE ' || v_quoted_name || ' FORCE';
                DBMS_OUTPUT.PUT_LINE('Dropped type: ' || t.type_name);
                v_dropped := v_dropped + 1;
            EXCEPTION
                WHEN OTHERS THEN
                    -- Ignore and try again next iteration
                    NULL;
            END;
        END LOOP;
        
        -- Re-query remaining types after drop attempts
        SELECT COUNT(*) INTO v_remaining 
        FROM user_types WHERE typecode IN ('OBJECT', 'COLLECTION');
        
        DBMS_OUTPUT.PUT_LINE('Iteration ' || v_iterations || ': Dropped ' || v_dropped || ' types, ' || v_remaining || ' remaining');
    END LOOP;
END;
/

-- =============================================================================
-- STEP 9: DROP ALL PROCEDURES AND FUNCTIONS
-- =============================================================================
BEGIN
    FOR p IN (SELECT object_name, object_type 
              FROM user_objects 
              WHERE object_type IN ('PROCEDURE', 'FUNCTION')) LOOP
        DECLARE
            v_quoted_name VARCHAR2(130);
        BEGIN
            v_quoted_name := DBMS_ASSERT.ENQUOTE_NAME(p.object_name, FALSE);
            EXECUTE IMMEDIATE 'DROP ' || p.object_type || ' ' || v_quoted_name;
            DBMS_OUTPUT.PUT_LINE('Dropped ' || p.object_type || ': ' || p.object_name);
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('Error dropping ' || p.object_type || ' ' || p.object_name || ': ' || SQLERRM);
        END;
    END LOOP;
END;
/

-- =============================================================================
-- STEP 10: DROP ALL SYNONYMS
-- =============================================================================
BEGIN
    FOR s IN (SELECT synonym_name FROM user_synonyms) LOOP
        DECLARE
            v_quoted_name VARCHAR2(130);
        BEGIN
            v_quoted_name := DBMS_ASSERT.ENQUOTE_NAME(s.synonym_name, FALSE);
            EXECUTE IMMEDIATE 'DROP SYNONYM ' || v_quoted_name;
            DBMS_OUTPUT.PUT_LINE('Dropped synonym: ' || s.synonym_name);
        EXCEPTION
            WHEN OTHERS THEN
                DBMS_OUTPUT.PUT_LINE('Error dropping synonym ' || s.synonym_name || ': ' || SQLERRM);
        END;
    END LOOP;
END;
/

COMMIT;

-- =============================================================================
-- VERIFICATION
-- =============================================================================
PROMPT;
PROMPT =====================================================
PROMPT Verification - Objects remaining (should all be 0):
PROMPT =====================================================

SELECT 'Tables: ' || COUNT(*) AS remaining FROM user_tables;
SELECT 'Views: ' || COUNT(*) AS remaining FROM user_views;
SELECT 'Sequences: ' || COUNT(*) AS remaining FROM user_sequences;
SELECT 'Types: ' || COUNT(*) AS remaining FROM user_types;
SELECT 'Packages: ' || COUNT(*) AS remaining FROM user_objects WHERE object_type = 'PACKAGE';
SELECT 'Procedures: ' || COUNT(*) AS remaining FROM user_objects WHERE object_type = 'PROCEDURE';
SELECT 'Functions: ' || COUNT(*) AS remaining FROM user_objects WHERE object_type = 'FUNCTION';
SELECT 'Materialized Views: ' || COUNT(*) AS remaining FROM user_mviews;
SELECT 'Synonyms: ' || COUNT(*) AS remaining FROM user_synonyms;
SELECT 'Triggers: ' || COUNT(*) AS remaining FROM user_triggers;

PROMPT;
PROMPT =====================================================
PROMPT Schema cleanup COMPLETE. Ready for fresh migration.
PROMPT =====================================================
