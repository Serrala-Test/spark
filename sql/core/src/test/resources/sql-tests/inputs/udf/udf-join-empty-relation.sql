-- List of configuration the test suite is run against:
--SET spark.sql.autoBroadcastJoinThreshold=10485760
--SET spark.sql.autoBroadcastJoinThreshold=-1,spark.sql.join.preferSortMergeJoin=true
--SET spark.sql.autoBroadcastJoinThreshold=-1,spark.sql.join.preferSortMergeJoin=false

-- This test file was converted from join-empty-relation.sql.
-- Note that currently registered UDF returns a string. So there are some differences, for instance
-- in string cast within UDF in Scala and Python.

CREATE TEMPORARY VIEW t1 AS SELECT * FROM VALUES (1) AS GROUPING(a);
CREATE TEMPORARY VIEW t2 AS SELECT * FROM VALUES (1) AS GROUPING(a);

CREATE TEMPORARY VIEW empty_table as SELECT a FROM t2 WHERE false;

SELECT udf(t1.a), udf(empty_table.a) FROM t1 INNER JOIN empty_table ON (udf(t1.a) = udf(empty_table.a));
SELECT udf(t1.a), udf(empty_table.a) FROM t1 CROSS JOIN empty_table ON (udf(t1.a) = udf(empty_table.a));
SELECT udf(t1.a), empty_table.a FROM t1 LEFT OUTER JOIN empty_table ON (udf(t1.a) = udf(empty_table.a));
SELECT udf(t1.a), udf(empty_table.a) FROM t1 RIGHT OUTER JOIN empty_table ON (udf(t1.a) = udf(empty_table.a));
SELECT udf(t1.a), empty_table.a FROM t1 FULL OUTER JOIN empty_table ON (udf(t1.a) = udf(empty_table.a));
SELECT udf(t1.a) FROM t1 LEFT SEMI JOIN empty_table ON (udf(t1.a) = udf(empty_table.a));
SELECT udf(t1.a) FROM t1 LEFT ANTI JOIN empty_table ON (udf(t1.a) = udf(empty_table.a));

SELECT udf(empty_table.a), udf(t1.a) FROM empty_table INNER JOIN t1 ON (udf(empty_table.a) = udf(t1.a));
SELECT udf(empty_table.a), udf(t1.a) FROM empty_table CROSS JOIN t1 ON (udf(empty_table.a) = udf(t1.a));
SELECT udf(empty_table.a), udf(t1.a) FROM empty_table LEFT OUTER JOIN t1 ON (udf(empty_table.a) = udf(t1.a));
SELECT empty_table.a, udf(t1.a) FROM empty_table RIGHT OUTER JOIN t1 ON (udf(empty_table.a) = udf(t1.a));
SELECT empty_table.a, udf(t1.a) FROM empty_table FULL OUTER JOIN t1 ON (udf(empty_table.a) = udf(t1.a));
SELECT empty_table.a FROM empty_table LEFT SEMI JOIN t1 ON (udf(empty_table.a) = udf(t1.a));
SELECT empty_table.a FROM empty_table LEFT ANTI JOIN t1 ON (udf(empty_table.a) = udf(t1.a));

SELECT udf(empty_table.a) FROM empty_table INNER JOIN empty_table AS empty_table2 ON (udf(empty_table.a) = udf(empty_table2.a));
SELECT udf(empty_table.a) FROM empty_table CROSS JOIN empty_table AS empty_table2 ON (udf(empty_table.a) = udf(empty_table2.a));
SELECT udf(empty_table.a) FROM empty_table LEFT OUTER JOIN empty_table AS empty_table2 ON (udf(empty_table.a) = udf(empty_table2.a));
SELECT udf(empty_table.a) FROM empty_table RIGHT OUTER JOIN empty_table AS empty_table2 ON (udf(empty_table.a) = udf(empty_table2.a));
SELECT udf(empty_table.a) FROM empty_table FULL OUTER JOIN empty_table AS empty_table2 ON (udf(empty_table.a) = udf(empty_table2.a));
SELECT udf(empty_table.a) FROM empty_table LEFT SEMI JOIN empty_table AS empty_table2 ON (udf(empty_table.a) = udf(empty_table2.a));
SELECT udf(empty_table.a) FROM empty_table LEFT ANTI JOIN empty_table AS empty_table2 ON (udf(empty_table.a) = udf(empty_table2.a));
