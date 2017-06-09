CREATE OR REPLACE TEMPORARY VIEW t1 AS VALUES (1, 'a'), (2, 'b') tbl(c1, c2);
CREATE OR REPLACE TEMPORARY VIEW t2 AS VALUES (1.0, 1), (2.0, 4) tbl(c1, c2);

-- Simple Union
SELECT *
FROM   (SELECT * FROM t1
        UNION ALL
        SELECT * FROM t1) T;

-- Type Coerced Union
SELECT *
FROM   (SELECT * FROM t1
        UNION ALL
        SELECT * FROM t2
        UNION ALL
        SELECT * FROM t2) T;

-- Regression test for SPARK-18622
SELECT a
FROM (SELECT 0 a, 0 b
      UNION ALL
      SELECT SUM(1) a, CAST(0 AS BIGINT) b
      UNION ALL SELECT 0 a, 0 b) T;

-- Regression test for SPARK-18841 Push project through union should not be broken by redundant alias removal.
CREATE OR REPLACE TEMPORARY VIEW p1 AS VALUES 1 T(col);
CREATE OR REPLACE TEMPORARY VIEW p2 AS VALUES 1 T(col);
CREATE OR REPLACE TEMPORARY VIEW p3 AS VALUES 1 T(col);
SELECT 1 AS x,
       col
FROM   (SELECT col AS col
        FROM (SELECT p1.col AS col
              FROM   p1 CROSS JOIN p2
              UNION ALL
              SELECT col
              FROM p3) T1) T2;

-- Clean-up
DROP VIEW IF EXISTS t1;
DROP VIEW IF EXISTS t2;
DROP VIEW IF EXISTS p1;
DROP VIEW IF EXISTS p2;
DROP VIEW IF EXISTS p3;
