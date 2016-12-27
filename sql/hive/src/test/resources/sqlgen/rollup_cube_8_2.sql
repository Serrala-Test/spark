-- This file is automatically generated by LogicalPlanToSQLSuite.
SELECT hkey AS k1, value - 5 AS k2, hash(grouping_id()) AS hgid
FROM (SELECT hash(key) as hkey, key as value FROM parquet_t1) t GROUP BY hkey, value-5
WITH CUBE
--------------------------------------------------------------------------------
SELECT `gen_attr_3` AS `k1`, `gen_attr_4` AS `k2`, `gen_attr_5` AS `hgid` FROM (SELECT `gen_attr_6` AS `gen_attr_3`, (`gen_attr_7` - CAST(5 AS BIGINT)) AS `gen_attr_4`, hash(grouping_id()) AS `gen_attr_5` FROM (SELECT hash(`gen_attr_10`) AS `gen_attr_6`, `gen_attr_10` AS `gen_attr_7` FROM (SELECT `key` AS `gen_attr_10`, `value` AS `gen_attr_11` FROM `default`.`parquet_t1`) AS gen_subquery_0) AS t GROUP BY `gen_attr_6`, (`gen_attr_7` - CAST(5 AS BIGINT)) GROUPING SETS((`gen_attr_6`, (`gen_attr_7` - CAST(5 AS BIGINT))), (`gen_attr_6`), ((`gen_attr_7` - CAST(5 AS BIGINT))), ())) AS gen_subquery_1
