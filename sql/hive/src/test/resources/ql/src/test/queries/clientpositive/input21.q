

CREATE TABLE src_null(a STRING, b STRING, c STRING, d STRING) STORED AS TEXTFILE;
LOAD DATA LOCAL INPATH '../data/files/null.txt' INTO TABLE src_null;

EXPLAIN SELECT * FROM src_null DISTRIBUTE BY c SORT BY d;

SELECT * FROM src_null DISTRIBUTE BY c SORT BY d;


