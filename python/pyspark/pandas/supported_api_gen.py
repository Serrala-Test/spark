import os
from enum import Enum, unique
from inspect import getmembers, isclass, isfunction, signature
from typing import Callable, Dict, List, Set, TextIO, Tuple

import pyspark.pandas as ps
import pyspark.pandas.groupby as psg
import pyspark.pandas.window as psw
from pyspark.find_spark_home import _find_spark_home

import pandas as pd
import pandas.core.groupby as pdg
import pandas.core.window as pdw

MAX_MISSING_PARAMS_SIZE = 5
COMMON_PARAMETER_SET = {"kwargs", "args", "cls"}
MODULE_GROUP_MATCH = [(pd, ps), (pdw, psw), (pdg, psg)]

FILE_PATH_PREFIX = "./user_guide/pandas_on_spark"
SPARK_HOME = _find_spark_home()
TARGET_RST_FILE = os.path.join(
    SPARK_HOME, "python/docs/source/user_guide/pandas_on_spark/supported_pandas_api.rst"
)
RST_HEADER = """
..  Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

..    http://www.apache.org/licenses/LICENSE-2.0

..  Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.


=====================
Supported pandas APIs
=====================

.. currentmodule:: pyspark.pandas

The following table shows the pandas APIs that implemented or non-implemented from pandas API on
Spark.

Some pandas APIs do not implement full parameters, so the third column shows missing parameters for
each API.

'Y' in the second column means it's implemented including its whole parameter.
'N' means it's not implemented yet.
'P' means it's partially implemented with the missing of some parameters.

If there is non-implemented pandas API or parameter you want, you can create an `Apache Spark
JIRA <https://issues.apache.org/jira/projects/SPARK/summary>`__ to request or to contribute by your
own.

The API list is updated based on the `latest pandas official API
reference <https://pandas.pydata.org/docs/reference/index.html#>`__.

All implemented APIs listed here are distributed except the ones that requires the local
computation by design. For example, `DataFrame.to_numpy() <https://spark.apache.org
/docs/latest/api/python/reference/pyspark.pandas/api/pyspark.pandas.DataFrame.
to_numpy.html>`__ requires to collect the data to the driver side.

"""


@unique
class Implemented(Enum):
    IMPLEMENTED = "Y"
    NOT_IMPLEMENTED = "N"
    PARTIALLY_IMPLEMENTED = "P"


class SupportedStatus:
    def __init__(self, implemented: str, missing: str = ""):
        self.implemented = implemented
        self.missing = missing


def generate_supported_api() -> None:
    all_supported_status = {}
    for pd_module_group, ps_module_group in MODULE_GROUP_MATCH:
        pd_modules = get_pd_modules(pd_module_group)
        update_all_supported_status(
            all_supported_status, pd_modules, pd_module_group, ps_module_group
        )
    write_rst(all_supported_status)


def create_supported_by_module(
    module_name: str, pd_module_group, ps_module_group
) -> Dict[str, SupportedStatus]:
    pd_module = (
        getattr(pd_module_group, module_name) if module_name else pd_module_group
    )
    try:
        ps_module = (
            getattr(ps_module_group, module_name) if module_name else ps_module_group
        )
    except AttributeError:
        # module not implemented
        return {}

    pd_funcs = dict(
        [m for m in getmembers(pd_module, isfunction) if not m[0].startswith("_")]
    )
    if not pd_funcs:
        return {}

    ps_funcs = dict(
        [m for m in getmembers(ps_module, isfunction) if not m[0].startswith("_")]
    )

    return organize_by_implementation_status(
        module_name, pd_funcs, ps_funcs, pd_module_group, ps_module_group
    )


def organize_by_implementation_status(
    module_name: str,
    pd_funcs: Dict[str, Callable],
    ps_funcs: Dict[str, Callable],
    pd_module_group,
    ps_module_group,
) -> Dict[str, SupportedStatus]:
    pd_dict = {}
    for pd_func_name, pd_func in pd_funcs.items():
        ps_func = ps_funcs.get(pd_func_name)
        if ps_func:
            missing_set = (
                set(signature(pd_func).parameters)
                - set(signature(ps_func).parameters)
                - COMMON_PARAMETER_SET
            )
            if missing_set:
                # partially implemented
                pd_dict[pd_func_name] = SupportedStatus(
                    Implemented.PARTIALLY_IMPLEMENTED.value,
                    transform_missing(
                        module_name,
                        pd_func_name,
                        missing_set,
                        pd_module_group.__name__,
                        ps_module_group.__name__,
                    ),
                )
            else:
                # implemented including it's whole parameter
                pd_dict[pd_func_name] = SupportedStatus(Implemented.IMPLEMENTED.value)
        else:
            # not implemented yet
            pd_dict[pd_func_name] = SupportedStatus(Implemented.NOT_IMPLEMENTED.value)
    return pd_dict


def transform_missing(
    module_name: str,
    pd_func_name: str,
    missing_set: Set[str],
    pd_module_path: str,
    ps_module_path: str,
) -> str:
    missing_str = " , ".join(
        f"``{x}``" for x in sorted(missing_set)[:MAX_MISSING_PARAMS_SIZE]
    )
    if len(missing_set) > MAX_MISSING_PARAMS_SIZE:
        module_dot_func = (
            f"{module_name}.{pd_func_name}" if module_name else pd_func_name
        )
        additional_str = (
            " and more. See the "
            f"`{pd_module_path}.{module_dot_func} "
            "<https://pandas.pydata.org/docs/reference/api/"
            f"{pd_module_path}.{module_dot_func}.html>`__ and "
            f"`{ps_module_path}.{module_dot_func} "
            "<https://spark.apache.org/docs/latest/api/python/reference/pyspark.pandas/api/"
            f"{ps_module_path}.{module_dot_func}.html>`__ for detail."
        )
        missing_str += additional_str
    return missing_str


def get_pd_modules(pd_module_group) -> List[str]:
    return sorted(
        [m[0] for m in getmembers(pd_module_group, isclass) if not m[0].startswith("_")]
    )


def update_all_supported_status(
    all_supported_status: Dict[Tuple[str, str], Dict[str, SupportedStatus]],
    pd_modules: List[str],
    pd_module_group,
    ps_module_group,
) -> None:
    pd_modules += [""]  # for General Function APIs
    for module_name in pd_modules:
        supported_status = create_supported_by_module(
            module_name, pd_module_group, ps_module_group
        )
        if supported_status:
            all_supported_status[
                (module_name, ps_module_group.__name__)
            ] = supported_status


def write_table(
    module_name: str,
    module_path: str,
    supported_status: Dict[str, SupportedStatus],
    w_fd: TextIO,
) -> None:
    lines = []
    lines.append("Supported ")
    if module_name:
        lines.append(module_name)
    else:
        lines.append("General Function")
    lines.append(" APIs\n")
    lines.append("-" * 100)
    lines.append("\n")
    lines.append(f".. currentmodule:: {module_path}")
    if module_name:
        lines.append(f".{module_name}\n")
    else:
        lines.append("\n")
    lines.append("\n")
    lines.append(".. list-table::\n")
    lines.append("    :header-rows: 1\n")
    lines.append("\n")
    lines.append("    * - API\n")
    lines.append("      - Implemented\n")
    lines.append("      - Missing parameters\n")
    for func_str, status in supported_status.items():
        func_str = escape_func_str(func_str)
        if status.implemented == Implemented.NOT_IMPLEMENTED.value:
            lines.append(f"    * - {func_str}\n")
        else:
            lines.append(f"    * - :func:`{func_str}`\n")
        lines.append(f"      - {status.implemented}\n")
        lines.append("      - \n") if not status.missing else lines.append(
            f"      - {status.missing}\n"
        )
    w_fd.writelines(lines)


def escape_func_str(func_str: str) -> str:
    if func_str.endswith("_"):
        return func_str[:-1] + "\_"  # noqa: W605
    else:
        return func_str


def write_rst(
    all_supported_status: Dict[Tuple[str, str], Dict[str, SupportedStatus]]
) -> None:
    with open(TARGET_RST_FILE, "w") as w_fd:
        w_fd.write(RST_HEADER)
        for module_info, supported_status in all_supported_status.items():
            module, module_path = module_info
            if supported_status:
                write_table(module, module_path, supported_status, w_fd)
                w_fd.write("\n")
