-- This file is automatically generated by LogicalPlanToSQLSuite.
SELECT key, value, MAX(value) OVER (PARTITION BY key % 5
ORDER BY key ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW EXCLUDE TIES
) AS MAX FROM parquet_t1 GROUP BY key, value
--------------------------------------------------------------------------------
SELECT `gen_attr_0` AS `key`, `gen_attr_1` AS `value`, `gen_attr_2` AS `MAX` FROM (SELECT `gen_attr_0`, `gen_attr_1`, `gen_attr_2` FROM (SELECT gen_subquery_1.`gen_attr_0`, gen_subquery_1.`gen_attr_1`, gen_subquery_1.`gen_attr_3`, max(`gen_attr_1`) OVER (PARTITION BY `gen_attr_3` ORDER BY `gen_attr_0` ASC NULLS FIRST ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW EXCLUDE TIES) AS `gen_attr_2` FROM (SELECT `gen_attr_0`, `gen_attr_1`, (`gen_attr_0` % CAST(5 AS BIGINT)) AS `gen_attr_3` FROM (SELECT `key` AS `gen_attr_0`, `value` AS `gen_attr_1` FROM `default`.`parquet_t1`) AS gen_subquery_0 GROUP BY `gen_attr_0`, `gen_attr_1`) AS gen_subquery_1) AS gen_subquery_2) AS parquet_t1
