package catalyst
package execution

import java.io._

import util._

/**
 * Runs the test cases that are included in the hive distribution.
 */
class HiveCompatibility extends HiveQueryFileTest {
  // TODO: bundle in jar files... get from classpath
  lazy val hiveQueryDir = new File(TestShark.hiveDevHome, "ql/src/test/queries/clientpositive")
  def testCases = hiveQueryDir.listFiles.map(f => f.getName.stripSuffix(".q") -> f)

  /** A list of tests deemed out of scope currently and thus completely disregarded */
  override def blackList = Seq(
    "hook_order", // These tests use hooks that are not on the classpath and thus break all subsequent SQL execution.
    "hook_context",
    "mapjoin_hook",
    "multi_sahooks",
    "overridden_confs",
    "query_properties",
    "sample10",
    "updateAccessTime",
    "index_compact_binary_search",
    "bucket_num_reducers",

    // User specific test answers, breaks the caching mechanism.
    "authorization_3",
    "authorization_5",
    "keyword_1",
    "misc_json",
    "create_like_tbl_props",

    // Timezone specific test answers.
    "udf_unix_timestamp",
    "udf_to_unix_timestamp",

    // Cant run without local map/reduce.
    "index_auto_update",
    "index_auto_self_join",
    "index_stale",
    "type_cast_1",
    "index_compression",
    "index_bitmap_compression",
    "index_auto_multiple",
    "index_auto_mult_tables_compact",
    "index_auto_mult_tables",
    "index_auto_file_format",
    "index_auth",

    // Hive seems to think 1.0 > NaN = true && 1.0 < NaN = false... which is wrong.
    // http://stackoverflow.com/a/1573715
    "ops_comparison",

    // The skewjoin test seems to never complete on hive...
    "skewjoin",

    // These tests fail and and exit the JVM.
    "auto_join18_multi_distinct",
    "join18_multi_distinct",
    "input44",
    "input42",
    "input_dfs",
    "metadata_export_drop",
    "repair",

    // Uses a serde that isn't on the classpath... breaks other tests.
    "bucketizedhiveinputformat",

    // Avro tests seem to change the output format permanently thus breaking the answer cache, until
    // we figure out why this is the case let just ignore all of avro related tests.
    ".*avro.*",

    // Unique joins are weird and will require a lot of hacks (see comments in hive parser).
    "uniquejoin",

    // Hive seems to get the wrong answer on some outer joins.  MySQL agrees with catalyst.
    "auto_join29",

    // No support for multi-alias i.e. udf as (e1, e2, e3).
    "allcolref_in_udf",

    // No support for TestSerDe (not published afaik)
    "alter1",
    "input16",

    // Shark does not support buckets.
    ".*bucket.*",

    // No window support yet
    ".* window.*",

    // Fails in hive with authorization errors.
    "alter_rename_partition_authorization"
  )

  /**
   * The set of tests that are believed to be working in catalyst. Tests not in whiteList
   * blacklist are implicitly marked as ignored.
   */
  override def whiteList = Seq(
    "add_part_exist",
    "add_partition_no_whitelist",
    "add_partition_with_whitelist",
    "alias_casted_column",
    "alter4",
    "alter_index",
    "alter_partition_format_loc",
    "alter_partition_with_whitelist",
    "alter_table_serde",
    "ambiguous_col",
    "authorization_3",
    "authorization_5",
    "auto_join21",
    "auto_join23",
    "auto_join24",
    "auto_join26",
    "auto_join28",
    "auto_join_nulls",
    "auto_sortmerge_join_1",
    "auto_sortmerge_join_10",
    "auto_sortmerge_join_11",
    "auto_sortmerge_join_12",
    "auto_sortmerge_join_15",
    "auto_sortmerge_join_2",
    "auto_sortmerge_join_3",
    "auto_sortmerge_join_4",
    "auto_sortmerge_join_5",
    "auto_sortmerge_join_6",
    "auto_sortmerge_join_7",
    "auto_sortmerge_join_8",
    "auto_sortmerge_join_9",
    "binarysortable_1",
    "bucket1",
    "bucket_map_join_1",
    "bucket_map_join_2",
    "bucketcontext_5",
    "bucketmapjoin6",
    "bucketmapjoin_negative3",
    "combine1",
    "convert_enum_to_string",
    "correlationoptimizer15",
    "correlationoptimizer4",
    "correlationoptimizer6",
    "correlationoptimizer7",
    "count",
    "create_like2",
    "create_like_tbl_props",
    "create_view_translate",
    "ct_case_insensitive",
    "database_properties",
    "default_partition_name",
    "delimiter",
    "desc_non_existent_tbl",
    "describe_database_json",
    "describe_table_json",
    "describe_formatted_view_partitioned",
    "describe_formatted_view_partitioned_json",
    "describe_pretty",
    "describe_syntax",
    "diff_part_input_formats",
    "disable_file_format_check",
    "drop_function",
    "drop_index",
    "drop_partitions_filter",
    "drop_partitions_filter2",
    "drop_partitions_filter3",
    "drop_partitions_ignore_protection",
    "drop_table",
    "drop_table2",
    "drop_view",
    "escape_orderby1",
    "escape_sortby1",
    "filter_join_breaktask",
    "groupby1",
    "groupby1_map",
    "groupby1_map_nomap",
    "groupby1_map_skew",
    "groupby1_noskew",
    "groupby4_map",
    "groupby4_map_skew",
    "groupby5",
    "groupby5_map",
    "groupby5_map_skew",
    "groupby5_noskew",
    "groupby7",
    "groupby7_map",
    "groupby7_map_multi_single_reducer",
    "groupby7_map_skew",
    "groupby7_noskew",
    "groupby8_map",
    "groupby8_map_skew",
    "groupby8_noskew",
    "groupby_multi_single_reducer2",
    "groupby_mutli_insert_common_distinct",
    "groupby_sort_6",
    "groupby_sort_8",
    "groupby_sort_test_1",
    "implicit_cast1",
    "index_auto_self_join",
    "index_auto_update",
    "index_stale",
    "index_auth",
    "index_auto_file_format",
    "index_auto_mult_tables",
    "index_auto_mult_tables_compact",
    "index_auto_multiple",
    "index_bitmap_compression",
    "index_compression",
    "innerjoin",
    "inoutdriver",
    "input",
    "input0",
    "input11",
    "input11_limit",
    "input1_limit",
    "input22",
    "input23",
    "input24",
    "input25",
    "input28",
    "input2_limit",
    "input41",
    "input4_cb_delim",
    "input4_limit",
    "input6",
    "input7",
    "input8",
    "input9",
    "input_limit",
    "input_part1",
    "input_part2",
    "inputddl4",
    "inputddl7",
    "inputddl8",
    "insert_compressed",
    "join0",
    "join1",
    "join10",
    "join11",
    "join12",
    "join13",
    "join14",
    "join14_hadoop20",
    "join15",
    "join16",
    "join17",
    "join18",
    "join19",
    "join2",
    "join20",
    "join21",
    "join22",
    "join23",
    "join24",
    "join25",
    "join26",
    "join27",
    "join28",
    "join29",
    "join3",
    "join30",
    "join31",
    "join32",
    "join33",
    "join34",
    "join35",
    "join36",
    "join37",
    "join38",
    "join39",
    "join4",
    "join40",
    "join41",
    "join5",
    "join6",
    "join7",
    "join8",
    "join9",
    "join_casesensitive",
    "join_empty",
    "join_hive_626",
    "join_nulls",
    "join_reorder2",
    "join_reorder3",
    "join_reorder4",
    "join_star",
    "join_view",
    "keyword_1",
    "lineage1",
    "literal_double",
    "literal_ints",
    "literal_string",
    "load_file_with_space_in_the_name",
    "louter_join_ppr",
    "mapjoin_mapjoin",
    "mapjoin_subquery",
    "mapjoin_subquery2",
    "mapjoin_test_outer",
    "mapreduce3",
    "merge1",
    "merge2",
    "mergejoins",
    "mergejoins_mixed",
    "misc_json",
    "multi_join_union",
    "multigroupby_singlemr",
    "noalias_subq1",
    "nomore_ambiguous_table_col",
    "notable_alias1",
    "notable_alias2",
    "nullgroup",
    "nullgroup2",
    "nullgroup3",
    "nullgroup5",
    "nullinput",
    "nullinput2",
    "nullscript",
    "optional_outer",
    "order",
    "order2",
    "outer_join_ppr",
    "part_inherit_tbl_props",
    "part_inherit_tbl_props_empty",
    "partition_schema1",
    "partitions_json",
    "plan_json",
    "ppd1",
    "ppd_constant_where",
    "ppd_gby",
    "ppd_gby_join",
    "ppd_join",
    "ppd_join2",
    "ppd_join3",
    "ppd_outer_join1",
    "ppd_outer_join2",
    "ppd_outer_join3",
    "ppd_outer_join4",
    "ppd_outer_join5",
    "ppd_random",
    "ppd_repeated_alias",
    "ppd_udf_col",
    "ppd_union",
    "progress_1",
    "protectmode",
    "push_or",
    "query_with_semi",
    "quote2",
    "rename_column",
    "router_join_ppr",
    "select_as_omitted",
    "select_unquote_and",
    "select_unquote_not",
    "select_unquote_or",
    "serde_reported_schema",
    "set_variable_sub",
    "show_describe_func_quotes",
    "show_functions",
    "skewjoinopt13",
    "skewjoinopt18",
    "skewjoinopt9",
    "smb_mapjoin_10",
    "smb_mapjoin_13",
    "smb_mapjoin_14",
    "smb_mapjoin_15",
    "smb_mapjoin_16",
    "smb_mapjoin_17",
    "smb_mapjoin_21",
    "sort",
    "sort_merge_join_desc_1",
    "sort_merge_join_desc_2",
    "sort_merge_join_desc_3",
    "sort_merge_join_desc_4",
    "sort_merge_join_desc_5",
    "sort_merge_join_desc_6",
    "sort_merge_join_desc_7",
    "subq2",
    "tablename_with_select",
    "udf2",
    "udf9",
    "udf_10_trims",
    "udf_abs",
    "udf_acos",
    "udf_add",
    "udf_ascii",
    "udf_asin",
    "udf_atan",
    "udf_avg",
    "udf_bigint",
    "udf_bin",
    "udf_bitwise_and",
    "udf_bitwise_not",
    "udf_bitwise_or",
    "udf_bitwise_xor",
    "udf_boolean",
    "udf_ceil",
    "udf_ceiling",
    "udf_concat",
    "udf_concat_insert2",
    "udf_conv",
    "udf_cos",
    "udf_count",
    "udf_date_add",
    "udf_date_sub",
    "udf_datediff",
    "udf_day",
    "udf_dayofmonth",
    "udf_div",
    "udf_double",
    "udf_exp",
    "udf_field",
    "udf_find_in_set",
    "udf_float",
    "udf_floor",
    "udf_format_number",
    "udf_from_unixtime",
    "udf_greaterthan",
    "udf_greaterthanorequal",
    "udf_hex",
    "udf_index",
    "udf_int",
    "udf_isnotnull",
    "udf_isnull",
    "udf_lcase",
    "udf_length",
    "udf_lessthan",
    "udf_lessthanorequal",
    "udf_ln",
    "udf_log",
    "udf_log10",
    "udf_log2",
    "udf_lower",
    "udf_lpad",
    "udf_ltrim",
    "udf_minute",
    "udf_modulo",
    "udf_month",
    "udf_negative",
    "udf_not",
    "udf_notequal",
    "udf_nvl",
    "udf_or",
    "udf_parse_url",
    "udf_positive",
    "udf_pow",
    "udf_power",
    "udf_rand",
    "udf_regexp_extract",
    "udf_regexp_replace",
    "udf_repeat",
    "udf_rlike",
    "udf_round",
    "udf_rpad",
    "udf_rtrim",
    "udf_second",
    "udf_sign",
    "udf_sin",
    "udf_smallint",
    "udf_space",
    "udf_sqrt",
    "udf_std",
    "udf_stddev",
    "udf_stddev_pop",
    "udf_stddev_samp",
    "udf_string",
    "udf_substring",
    "udf_subtract",
    "udf_sum",
    "udf_tan",
    "udf_tinyint",
    "udf_to_date",
    "udf_to_unix_timestamp",
    "udf_trim",
    "udf_ucase",
    "udf_unix_timestamp",
    "udf_upper",
    "udf_var_pop",
    "udf_var_samp",
    "udf_variance",
    "udf_weekofyear",
    "udf_when",
    "udf_xpath",
    "udf_xpath_boolean",
    "udf_xpath_double",
    "udf_xpath_float",
    "udf_xpath_int",
    "udf_xpath_long",
    "udf_xpath_short",
    "union10",
    "union11",
    "union13",
    "union14",
    "union15",
    "union16",
    "union17",
    "union18",
    "union19",
    "union2",
    "union20",
    "union27",
    "union28",
    "union29",
    "union30",
    "union31",
    "union34",
    "union4",
    "union5",
    "union6",
    "union7",
    "union8",
    "union9",
    "union_script",
    "varchar_2",
    "varchar_join1",
    "varchar_union1"
  )
}
