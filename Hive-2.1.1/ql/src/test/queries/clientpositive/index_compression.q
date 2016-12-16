set hive.mapred.mode=nonstrict;
SET hive.exec.compress.output=true;
SET hive.stats.dbclass=fs;

-- SORT_QUERY_RESULTS

CREATE INDEX src_index ON TABLE src(key) as 'COMPACT' WITH DEFERRED REBUILD;
ALTER INDEX src_index ON src REBUILD;

SET hive.input.format=org.apache.hadoop.hive.ql.io.HiveInputFormat;
SET hive.optimize.index.filter=true;
SET hive.optimize.index.filter.compact.minsize=0;

-- automatic indexing
EXPLAIN SELECT key, value FROM src WHERE key > 80 AND key < 100;
SELECT key, value FROM src WHERE key > 80 AND key < 100;

DROP INDEX src_index on src;
