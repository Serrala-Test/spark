/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.launcher;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for spark-submit command line options.
 * </p>
 * This class, although public, is not designed to be used outside of Spark.
 * <p/>
 * This class encapsulates the parsing code for spark-submit command line options, so that there
 * is a single list of options that needs to be maintained (well, sort of, but it makes it harder
 * to break things).
 */
public abstract class SparkSubmitOptionParser {

  // The following constants define the "main" name for the available options. They're defined
  // to avoid copy & paste of the raw strings where they're needed.
  protected static final String CLASS = "--class";
  protected static final String CONF = "--conf";
  protected static final String DEPLOY_MODE = "--deploy-mode";
  protected static final String DRIVER_CLASS_PATH = "--driver-class-path";
  protected static final String DRIVER_CORES = "--driver-cores";
  protected static final String DRIVER_JAVA_OPTIONS =  "--driver-java-options";
  protected static final String DRIVER_LIBRARY_PATH = "--driver-library-path";
  protected static final String DRIVER_MEMORY = "--driver-memory";
  protected static final String EXECUTOR_MEMORY = "--executor-memory";
  protected static final String FILES = "--files";
  protected static final String JARS = "--jars";
  protected static final String MASTER = "--master";
  protected static final String NAME = "--name";
  protected static final String PACKAGES = "--packages";
  protected static final String PROPERTIES_FILE = "--properties-file";
  protected static final String PY_FILES = "--py-files";
  protected static final String REPOSITORIES = "--repositories";
  protected static final String TOTAL_EXECUTOR_CORES = "--total-executor-cores";

  // Options that do not take arguments.
  protected static final String HELP = "--help";
  protected static final String SUPERVISE = "--supervise";
  protected static final String VERBOSE = "--verbose";

  // YARN-only options.
  protected static final String ARCHIVES = "--archives";
  protected static final String EXECUTOR_CORES = "--executor-cores";
  protected static final String QUEUE = "--queue";
  protected static final String NUM_EXECUTORS = "--num-executors";

  /**
   * This is the canonical list of spark-submit options. Each entry in the array contains the
   * different aliases for the same option; the first element of each entry is the "official"
   * name of the option, passed to {@link #handle(String, String)}.
   * <p/>
   * Options not listed here nor in the "switch" list below will result in a call to
   * {@link $#handleUnknown(String)}.
   */
  private final String[][] opts = {
    { ARCHIVES },
    { CLASS },
    { CONF, "-c" },
    { DEPLOY_MODE },
    { DRIVER_CLASS_PATH },
    { DRIVER_CORES },
    { DRIVER_JAVA_OPTIONS },
    { DRIVER_LIBRARY_PATH },
    { DRIVER_MEMORY },
    { EXECUTOR_CORES },
    { EXECUTOR_MEMORY },
    { FILES },
    { JARS },
    { MASTER },
    { NAME },
    { NUM_EXECUTORS },
    { PACKAGES },
    { PROPERTIES_FILE },
    { PY_FILES },
    { QUEUE },
    { REPOSITORIES },
    { TOTAL_EXECUTOR_CORES },
  };

  /**
   * List of switches (command line options that do not take parameters) recognized by spark-submit.
   */
  private final String[][] switches = {
    { HELP, "-h" },
    { SUPERVISE },
    { VERBOSE, "-v" },
  };

  /**
   * Parse a list of spark-submit command line options.
   * <p/>
   * See SparkSubmitArguments.scala for a more formal description of available options.
   *
   * @throws IllegalArgumentException If an error is found during parsing.
   */
  protected final void parse(List<String> args) {
    Pattern eqSeparatedOpt = Pattern.compile("(--[^=]+)=(.+)");

    int idx = 0;
    for (idx = 0; idx < args.size(); idx++) {
      String arg = args.get(idx);
      String value = null;

      Matcher m = eqSeparatedOpt.matcher(arg);
      if (m.matches()) {
        arg = m.group(1);
        value = m.group(2);
      }

      // Look for options with a value.
      String name = findCliOption(arg, opts);
      if (name != null) {
        if (value == null) {
          if (idx == args.size() - 1) {
            throw new IllegalArgumentException(
                String.format("Missing argument for option '%s'.", arg));
          }
          idx++;
          value = args.get(idx);
        }
        if (!handle(name, value)) {
          break;
        }
        continue;
      }

      // Look for a switch.
      name = findCliOption(arg, switches);
      if (name != null) {
        if (!handle(name, null)) {
          break;
        }
        continue;
      }

      if (!handleUnknown(arg)) {
        break;
      }
    }

    if (idx < args.size()) {
      idx++;
    }
    handleExtraArgs(args.subList(idx, args.size()));
  }

  /**
   * Callback for when an option with an argument is parsed.
   *
   * @param opt The long name of the cli option (might differ from actual command line).
   * @param value The value. This will be <i>null</i> if the option does not take a value.
   * @return Whether to continue parsing the argument list.
   */
  protected abstract boolean handle(String opt, String value);

  /**
   * Callback for when an unrecognized option is parsed.
   *
   * @param opt Unrecognized option from the command line.
   * @return Whether to continue parsing the argument list.
   */
  protected abstract boolean handleUnknown(String opt);

  /**
   * Callback for remaining command line arguments after either {@link #handle(String, String)} or
   * {@link #handleUnknown(String)} return "false". This will be called at the end of parsing even
   * when there are no remaining arguments.
   *
   * @param extra List of remaining arguments.
   */
  protected abstract void handleExtraArgs(List<String> extra);

  private String findCliOption(String name, String[][] available) {
    for (String[] candidates : available) {
      for (String candidate : candidates) {
        if (candidate.equals(name)) {
          return candidates[0];
        }
      }
    }
    return null;
  }

}
