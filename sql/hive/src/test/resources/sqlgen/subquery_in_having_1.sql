-- This file is automatically generated by LogicalPlanToSQLSuite.
select key, count(*)
from src
group by key
having count(*) in (select count(*) from src s1 where s1.key = '90' group by s1.key)
order by key
--------------------------------------------------------------------------------
SELECT `gen_attr_0` AS `key`, `gen_attr_1` AS `count(1)` FROM (SELECT `gen_attr_0`, `gen_attr_1` FROM (SELECT `gen_attr_0`, count(1) AS `gen_attr_1`, count(1) AS `gen_attr_2` FROM (SELECT `key` AS `gen_attr_0`, `value` AS `gen_attr_4` FROM `default`.`src`) AS gen_subquery_0 GROUP BY `gen_attr_0` HAVING (`gen_attr_2` IN (SELECT `gen_attr_5` AS `_c0` FROM (SELECT `gen_attr_3` AS `gen_attr_5` FROM (SELECT count(1) AS `gen_attr_3` FROM (SELECT `key` AS `gen_attr_6`, `value` AS `gen_attr_7` FROM `default`.`src`) AS gen_subquery_3 WHERE (CAST(`gen_attr_6` AS DOUBLE) = CAST('90' AS DOUBLE)) GROUP BY `gen_attr_6`) AS gen_subquery_2) AS gen_subquery_4))) AS gen_subquery_1 ORDER BY `gen_attr_0` ASC NULLS FIRST) AS src
