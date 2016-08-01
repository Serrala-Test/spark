-- This file is automatically generated by LogicalPlanToSQLSuite.
SELECT hkey AS k1, value - 5 AS k2, hash(grouping_id()) AS hgid
FROM (SELECT hash(key) as hkey, key as value FROM parquet_t1) t GROUP BY hkey, value-5
WITH CUBE
--------------------------------------------------------------------------------
SELECT t.`hkey` AS `k1`, (t.`value` - CAST(5 AS BIGINT)) AS `k2`, hash(grouping_id()) AS `hgid` FROM (SELECT t.`hkey`, t.`value`, t.`hkey` AS `hkey`, (t.`value` - CAST(5 AS BIGINT)) AS `(value - cast(5 as bigint))` FROM (SELECT hash(parquet_t1.`key`) AS `hkey`, parquet_t1.`key` AS `value` FROM parquet_t1) AS t) GROUP BY t.`hkey`, (t.`value` - CAST(5 AS BIGINT)) GROUPING SETS((t.`hkey`, (t.`value` - CAST(5 AS BIGINT))), (t.`hkey`), ((t.`value` - CAST(5 AS BIGINT))), ())
