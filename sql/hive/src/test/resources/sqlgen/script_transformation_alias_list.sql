-- This file is automatically generated by LogicalPlanToSQLSuite.
SELECT TRANSFORM (a, b, c, d) USING 'cat' AS (d1, d2, d3, d4) FROM parquet_t2
--------------------------------------------------------------------------------
SELECT `gen_attr_4` AS `d1`, `gen_attr_5` AS `d2`, `gen_attr_6` AS `d3`, `gen_attr_7` AS `d4` FROM (SELECT TRANSFORM (`gen_attr_0`, `gen_attr_1`, `gen_attr_2`, `gen_attr_3`) ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe' WITH SERDEPROPERTIES('field.delim' = '	') USING 'cat' AS (`gen_attr_4` string, `gen_attr_5` string, `gen_attr_6` string, `gen_attr_7` string) ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe' WITH SERDEPROPERTIES('field.delim' = '	') FROM (SELECT `a` AS `gen_attr_0`, `b` AS `gen_attr_1`, `c` AS `gen_attr_2`, `d` AS `gen_attr_3` FROM `default`.`parquet_t2`) AS gen_subquery_0) AS gen_subquery_1
