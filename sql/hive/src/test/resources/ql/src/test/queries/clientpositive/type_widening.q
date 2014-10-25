set hive.fetch.task.conversion=more;
-- Check for int, bigint automatic type widening conversions in UDFs, UNIONS
EXPLAIN SELECT COALESCE(0, 9223372036854775807) FROM src LIMIT 1;
SELECT COALESCE(0, 9223372036854775807) FROM src LIMIT 1;

EXPLAIN SELECT * FROM (SELECT 0 AS numcol FROM src UNION ALL SELECT 9223372036854775807 AS numcol FROM src) a ORDER BY numcol;
SELECT * FROM (SELECT 0 AS numcol FROM src UNION ALL SELECT 9223372036854775807 AS numcol FROM src) a ORDER BY numcol;
