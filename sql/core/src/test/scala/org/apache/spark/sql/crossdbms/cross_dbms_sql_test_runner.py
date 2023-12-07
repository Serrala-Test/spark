#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from pathlib import Path
from typing import List, Tuple, Optional, Callable, Dict

import sqlglot

from sql_test_runner import SQLQueryTestRunner, PostgresConnection
from helper import get_workspace_file_path, file_to_string, split_comments_and_codes, get_queries, TestCase, ExecutionOutput, format_postgres_output

# Argument in input files to indicate that the golden file should also be generated with a reference
# DBMS.
DBMS_TO_GENERATE_GOLDEN_FILE = "DBMS_TO_GENERATE_GOLDEN_FILE"

SPARK = "spark"
POSTGRES = "postgres"
SUPPORTED_DBMS = [POSTGRES]
DEFAULT_DBMS = POSTGRES

# Mapping from DBMS to connection creation function
DBMS_TO_CONNECTION_PROVIDER_MAPPING: Dict[str, Callable[[Optional[str]], SQLQueryTestRunner]] = {
    POSTGRES: lambda connection_url: SQLQueryTestRunner(PostgresConnection(connection_url))
}

# Mapping from DBMS to output formatter
DBMS_TO_OUTPUT_FORMATTER_MAPPING: Dict[str, Callable[[List[str]], str]] = {
    POSTGRES: lambda s: format_postgres_output(s)
}

class CrossDbmsSQLQueryTestRunner:

    def __init__(self, cross_dbms_to_generate_golden_files: str):
        self.base_resource_path = get_workspace_file_path()
        self.input_file_path = (self.base_resource_path / "inputs").resolve().absolute()
        self.cross_dbms_to_generate_golden_files = cross_dbms_to_generate_golden_files
        testcases = self._list_test_cases()
        for testcase in testcases:
            self.run_sql_test_case(testcase, testcases)

    def _result_file_for_input_file(self, file: Path) -> str:
        default_results_dir = Path(self.base_resource_path, "results")
        golden_file_path = Path(default_results_dir,
            f"{self.cross_dbms_to_generate_golden_files}-results").resolve().absolute()
        return str(file.resolve().absolute()).replace(
            str(self.input_file_path), str(golden_file_path)) + ".out"

    def _list_test_cases(self) -> List[TestCase]:
        def list_files_recursively(directory: Path) -> List[Path]:
            return [f for f in directory.rglob('*') if f.is_file()]

        test_cases = []
        for file in list_files_recursively(Path(self.input_file_path)):
            result_file = self._result_file_for_input_file(file)
            abs_path = file.resolve().absolute()
            test_case_name = abs_path.relative_to(self.input_file_path).as_posix()
            test_cases.append(TestCase(test_case_name, str(abs_path), result_file))

        return sorted(test_cases, key=lambda x: x.name)

    def translate_queries(self, query: str) -> str:
        return sqlglot.transpile(
            query, read=SPARK, write=self.cross_dbms_to_generate_golden_files)[0]

    def run_sql_test_case(self, test_case: TestCase, list_test_cases):
        input = file_to_string(test_case.input_file)
        comments, code = split_comments_and_codes(input)
        dbms = [comment[31:] for comment in comments if comment.startswith(
            f"--{DBMS_TO_GENERATE_GOLDEN_FILE}")]
        if self.cross_dbms_to_generate_golden_files not in dbms:
            return

        print(f'Running testcase\t{test_case.name}')
        queries = get_queries(code, comments, list_test_cases)
        self._run_queries_and_generate_golden_files(queries, test_case)

    def _run_queries_and_generate_golden_files(self, queries: List[str], test_case: TestCase):
        runner: SQLQueryTestRunner = DBMS_TO_CONNECTION_PROVIDER_MAPPING.get(self.cross_dbms_to_generate_golden_files)(None)
        results = []
        for query in queries:
            translated_query = self.translate_queries(query)
            try:
                output = runner.run_query(translated_query)
            except Exception as e:
                output = [str(e)]
            formatted_output = DBMS_TO_OUTPUT_FORMATTER_MAPPING.get(self.cross_dbms_to_generate_golden_files)(output)
            results.append(ExecutionOutput(sql=query, output=formatted_output))

        outputs = [str(s) for s in results]
        golden_output = f"-- Automatically generated by {type(self).__name__} with {self.cross_dbms_to_generate_golden_files}\n"
        golden_output += '\n\n\n'.join(outputs) + '\n'

        result_file = Path(test_case.result_file)
        parent = result_file.parent

        if not parent.exists():
            parent.mkdir(parents=True, exist_ok=True)

        with open(result_file, 'w', encoding='utf-8') as file:
            file.write(golden_output)

        runner.clean_up()


if __name__ == "__main__":
    # Extending this to other DBMS can be easily added in the future
    CrossDbmsSQLQueryTestRunner(DEFAULT_DBMS)
