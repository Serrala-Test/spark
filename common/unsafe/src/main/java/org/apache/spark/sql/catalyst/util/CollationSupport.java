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
package org.apache.spark.sql.catalyst.util;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.text.StringSearch;
import com.ibm.icu.util.ULocale;

import org.apache.spark.unsafe.UTF8StringBuilder;
import org.apache.spark.unsafe.types.UTF8String;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.apache.spark.unsafe.Platform.BYTE_ARRAY_OFFSET;
import static org.apache.spark.unsafe.Platform.copyMemory;

/**
 * Static entry point for collation-aware expressions (StringExpressions, RegexpExpressions, and
 * other expressions that require custom collation support), as well as private utility methods for
 * collation-aware UTF8String operations needed to implement .
 */
public final class CollationSupport {

  /**
   * Collation-aware string expressions.
   */

  public static class StringSplitSQL {
    public static UTF8String[] exec(final UTF8String s, final UTF8String d, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(s, d);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(s, d);
      } else {
        return execICU(s, d, collationId);
      }
    }
    public static String genCode(final String s, final String d, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringSplitSQL.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", s, d);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", s, d);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", s, d, collationId);
      }
    }
    public static UTF8String[] execBinary(final UTF8String string, final UTF8String delimiter) {
      return string.splitSQL(delimiter, -1);
    }
    public static UTF8String[] execLowercase(final UTF8String string, final UTF8String delimiter) {
      if (delimiter.numBytes() == 0) return new UTF8String[] { string };
      if (string.numBytes() == 0) return new UTF8String[] { UTF8String.EMPTY_UTF8 };
      Pattern pattern = Pattern.compile(Pattern.quote(delimiter.toString()),
        CollationSupport.lowercaseRegexFlags);
      String[] splits = pattern.split(string.toString(), -1);
      UTF8String[] res = new UTF8String[splits.length];
      for (int i = 0; i < res.length; i++) {
        res[i] = UTF8String.fromString(splits[i]);
      }
      return res;
    }
    public static UTF8String[] execICU(final UTF8String string, final UTF8String delimiter,
        final int collationId) {
      if (delimiter.numBytes() == 0) return new UTF8String[] { string };
      if (string.numBytes() == 0) return new UTF8String[] { UTF8String.EMPTY_UTF8 };
      List<UTF8String> strings = new ArrayList<>();
      String target = string.toString(), pattern = delimiter.toString();
      StringSearch stringSearch = CollationFactory.getStringSearch(target, pattern, collationId);
      int start = 0, end;
      while ((end = stringSearch.next()) != StringSearch.DONE) {
        strings.add(UTF8String.fromString(target.substring(start, end)));
        start = end + stringSearch.getMatchLength();
      }
      if (start <= target.length()) {
        strings.add(UTF8String.fromString(target.substring(start)));
      }
      return strings.toArray(new UTF8String[0]);
    }
  }

  public static class Contains {
    public static boolean exec(final UTF8String l, final UTF8String r, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(l, r);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(l, r);
      } else {
        return execICU(l, r, collationId);
      }
    }
    public static String genCode(final String l, final String r, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.Contains.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", l, r);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", l, r);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", l, r, collationId);
      }
    }
    public static boolean execBinary(final UTF8String l, final UTF8String r) {
      return l.contains(r);
    }
    public static boolean execLowercase(final UTF8String l, final UTF8String r) {
      if (r.numChars() == 0) return true;
      if (l.numChars() < r.numChars()) return false;
      return CollationAwareUTF8String.lowercaseIndexOf(l, r, 0) >= 0;
    }
    public static boolean execICU(final UTF8String l, final UTF8String r,
        final int collationId) {
      if (r.numBytes() == 0) return true;
      if (l.numBytes() == 0) return false;
      StringSearch stringSearch = CollationFactory.getStringSearch(l, r, collationId);
      return stringSearch.first() != StringSearch.DONE;
    }
  }

  public static class StartsWith {
    public static boolean exec(final UTF8String l, final UTF8String r,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(l, r);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(l, r);
      } else {
        return execICU(l, r, collationId);
      }
    }
    public static String genCode(final String l, final String r, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StartsWith.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", l, r);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", l, r);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", l, r, collationId);
      }
    }
    public static boolean execBinary(final UTF8String l, final UTF8String r) {
      return l.startsWith(r);
    }
    public static boolean execLowercase(final UTF8String l, final UTF8String r) {
      if (r.numBytes() == 0) return true;
      if (l.numChars() < r.numChars()) return false;
      return CollationAwareUTF8String.lowercaseMatchAt(l, r.toLowerCase(), 0, r.numChars());
    }
    public static boolean execICU(final UTF8String l, final UTF8String r,
        final int collationId) {
      if (r.numBytes() == 0) return true;
      if (l.numBytes() == 0) return false;
      StringSearch stringSearch = CollationFactory.getStringSearch(l, r, collationId);
      return stringSearch.first() == 0;
    }
  }

  public static class EndsWith {
    public static boolean exec(final UTF8String l, final UTF8String r, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(l, r);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(l, r);
      } else {
        return execICU(l, r, collationId);
      }
    }
    public static String genCode(final String l, final String r, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.EndsWith.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", l, r);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", l, r);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", l, r, collationId);
      }
    }
    public static boolean execBinary(final UTF8String l, final UTF8String r) {
      return l.endsWith(r);
    }
    public static boolean execLowercase(final UTF8String l, final UTF8String r) {
      if (r.numBytes() == 0) return true;
      if (l.numChars() < r.numChars()) return false;
      return CollationAwareUTF8String.lowercaseMatchAt(l, r.toLowerCase(),
        l.numChars() - r.numChars(), r.numChars());
    }
    public static boolean execICU(final UTF8String l, final UTF8String r,
        final int collationId) {
      if (r.numBytes() == 0) return true;
      if (l.numBytes() == 0) return false;
      StringSearch stringSearch = CollationFactory.getStringSearch(l, r, collationId);
      int endIndex = stringSearch.getTarget().getEndIndex();
      return stringSearch.last() == endIndex - stringSearch.getMatchLength();
    }
  }

  public static class Upper {
    public static UTF8String exec(final UTF8String v, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality || collation.supportsLowercaseEquality) {
        return execUTF8(v);
      } else {
        return execICU(v, collationId);
      }
    }
    public static String genCode(final String v, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.Upper.exec";
      if (collation.supportsBinaryEquality || collation.supportsLowercaseEquality) {
        return String.format(expr + "UTF8(%s)", v);
      } else {
        return String.format(expr + "ICU(%s, %d)", v, collationId);
      }
    }
    public static UTF8String execUTF8(final UTF8String v) {
      return v.toUpperCase();
    }
    public static UTF8String execICU(final UTF8String v, final int collationId) {
      return UTF8String.fromString(CollationAwareUTF8String.toUpperCase(v.toString(), collationId));
    }
  }

  public static class Lower {
    public static UTF8String exec(final UTF8String v, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality || collation.supportsLowercaseEquality) {
        return execUTF8(v);
      } else {
        return execICU(v, collationId);
      }
    }
    public static String genCode(final String v, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
        String expr = "CollationSupport.Lower.exec";
      if (collation.supportsBinaryEquality || collation.supportsLowercaseEquality) {
        return String.format(expr + "UTF8(%s)", v);
      } else {
        return String.format(expr + "ICU(%s, %d)", v, collationId);
      }
    }
    public static UTF8String execUTF8(final UTF8String v) {
      return v.toLowerCase();
    }
    public static UTF8String execICU(final UTF8String v, final int collationId) {
      return UTF8String.fromString(CollationAwareUTF8String.toLowerCase(v.toString(), collationId));
    }
  }

  public static class InitCap {
    public static UTF8String exec(final UTF8String v, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality || collation.supportsLowercaseEquality) {
        return execUTF8(v);
      } else {
        return execICU(v, collationId);
      }
    }

    public static String genCode(final String v, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.InitCap.exec";
      if (collation.supportsBinaryEquality || collation.supportsLowercaseEquality) {
        return String.format(expr + "UTF8(%s)", v);
      } else {
        return String.format(expr + "ICU(%s, %d)", v, collationId);
      }
    }

    public static UTF8String execUTF8(final UTF8String v) {
      return v.toLowerCase().toTitleCase();
    }

    public static UTF8String execICU(final UTF8String v, final int collationId) {
      return UTF8String.fromString(
              CollationAwareUTF8String.toTitleCase(
                      CollationAwareUTF8String.toLowerCase(
                              v.toString(),
                              collationId
                      ),
                      collationId));
    }
  }

  public static class FindInSet {
    public static int exec(final UTF8String word, final UTF8String set, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(word, set);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(word, set);
      } else {
        return execICU(word, set, collationId);
      }
    }
    public static String genCode(final String word, final String set, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.FindInSet.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", word, set);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", word, set);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", word, set, collationId);
      }
    }
    public static int execBinary(final UTF8String word, final UTF8String set) {
      return set.findInSet(word);
    }
    public static int execLowercase(final UTF8String word, final UTF8String set) {
      return set.toLowerCase().findInSet(word.toLowerCase());
    }
    public static int execICU(final UTF8String word, final UTF8String set,
                                  final int collationId) {
      return CollationAwareUTF8String.findInSet(word, set, collationId);
    }
  }

  public static class StringInstr {
    public static int exec(final UTF8String string, final UTF8String substring,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(string, substring);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(string, substring);
      } else {
        return execICU(string, substring, collationId);
      }
    }
    public static String genCode(final String string, final String substring,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringInstr.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", string, substring);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", string, substring);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", string, substring, collationId);
      }
    }
    public static int execBinary(final UTF8String string, final UTF8String substring) {
      return string.indexOf(substring, 0);
    }
    public static int execLowercase(final UTF8String string, final UTF8String substring) {
      return CollationAwareUTF8String.lowercaseIndexOf(string, substring, 0);
    }
    public static int execICU(final UTF8String string, final UTF8String substring,
        final int collationId) {
      return CollationAwareUTF8String.indexOf(string, substring, 0, collationId);
    }
  }

  public static class StringReplace {
    public static UTF8String exec(final UTF8String src, final UTF8String search,
        final UTF8String replace, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(src, search, replace);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(src, search, replace);
      } else {
        return execICU(src, search, replace, collationId);
      }
    }
    public static String genCode(final String src, final String search, final String replace,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringReplace.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s, %s)", src, search, replace);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s, %s)", src, search, replace);
      } else {
        return String.format(expr + "ICU(%s, %s, %s, %d)", src, search, replace, collationId);
      }
    }
    public static UTF8String execBinary(final UTF8String src, final UTF8String search,
        final UTF8String replace) {
      return src.replace(search, replace);
    }
    public static UTF8String execLowercase(final UTF8String src, final UTF8String search,
        final UTF8String replace) {
      return CollationAwareUTF8String.lowercaseReplace(src, search, replace);
    }
    public static UTF8String execICU(final UTF8String src, final UTF8String search,
        final UTF8String replace, final int collationId) {
      return CollationAwareUTF8String.replace(src, search, replace, collationId);
    }
  }

  public static class StringLocate {
    public static int exec(final UTF8String string, final UTF8String substring, final int start,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(string, substring, start);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(string, substring, start);
      } else {
        return execICU(string, substring, start, collationId);
      }
    }
    public static String genCode(final String string, final String substring, final int start,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringLocate.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s, %d)", string, substring, start);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s, %d)", string, substring, start);
      } else {
        return String.format(expr + "ICU(%s, %s, %d, %d)", string, substring, start, collationId);
      }
    }
    public static int execBinary(final UTF8String string, final UTF8String substring,
        final int start) {
      return string.indexOf(substring, start);
    }
    public static int execLowercase(final UTF8String string, final UTF8String substring,
        final int start) {
      return CollationAwareUTF8String.lowercaseIndexOf(string, substring, start);
    }
    public static int execICU(final UTF8String string, final UTF8String substring, final int start,
                              final int collationId) {
      return CollationAwareUTF8String.indexOf(string, substring, start, collationId);
    }
  }

  public static class SubstringIndex {
    public static UTF8String exec(final UTF8String string, final UTF8String delimiter,
        final int count, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(string, delimiter, count);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(string, delimiter, count);
      } else {
        return execICU(string, delimiter, count, collationId);
      }
    }
    public static String genCode(final String string, final String delimiter,
        final int count, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.SubstringIndex.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s, %d)", string, delimiter, count);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s, %d)", string, delimiter, count);
      } else {
        return String.format(expr + "ICU(%s, %s, %d, %d)", string, delimiter, count, collationId);
      }
    }
    public static UTF8String execBinary(final UTF8String string, final UTF8String delimiter,
        final int count) {
      return string.subStringIndex(delimiter, count);
    }
    public static UTF8String execLowercase(final UTF8String string, final UTF8String delimiter,
        final int count) {
      return CollationAwareUTF8String.lowercaseSubStringIndex(string, delimiter, count);
    }
    public static UTF8String execICU(final UTF8String string, final UTF8String delimiter,
        final int count, final int collationId) {
      return CollationAwareUTF8String.subStringIndex(string, delimiter, count,
              collationId);
    }
  }

  public static class StringTranslate {
    public static UTF8String exec(final UTF8String source, Map<String, String> dict,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(source, dict);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(source, dict);
      } else {
        return execICU(source, dict, collationId);
      }
    }
    public static String genCode(final String source, final String dict, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.EndsWith.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", source, dict);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", source, dict);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", source, dict, collationId);
      }
    }
    public static UTF8String execBinary(final UTF8String source, Map<String, String> dict) {
      return source.translate(dict);
    }
    public static UTF8String execLowercase(final UTF8String source, Map<String, String> dict) {
      String srcStr = source.toString();
      StringBuilder sb = new StringBuilder();
      int charCount = 0;
      for (int k = 0; k < srcStr.length(); k += charCount) {
        int codePoint = srcStr.codePointAt(k);
        charCount = Character.charCount(codePoint);
        String subStr = srcStr.substring(k, k + charCount);
        String translated = dict.get(subStr.toLowerCase());
        if (null == translated) {
          sb.append(subStr);
        } else if (!"\0".equals(translated)) {
          sb.append(translated);
        }
      }
      return UTF8String.fromString(sb.toString());
    }
    public static UTF8String execICU(final UTF8String source, Map<String, String> dict,
        final int collationId) {
      return source.translate(CollationAwareUTF8String.getCollationAwareDict(
        source, dict, collationId));
    }
  }

  // TODO: Add more collation-aware string expressions.

  /**
   * Collation-aware regexp expressions.
   */

  public static boolean supportsLowercaseRegex(final int collationId) {
    // for regex, only Unicode case-insensitive matching is possible,
    // so UTF8_BINARY_LCASE is treated as UNICODE_CI in this context
    return CollationFactory.fetchCollation(collationId).supportsLowercaseEquality;
  }

  private static final int lowercaseRegexFlags = Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE;
  public static int collationAwareRegexFlags(final int collationId) {
    return supportsLowercaseRegex(collationId) ? lowercaseRegexFlags : 0;
  }

  private static final UTF8String lowercaseRegexPrefix = UTF8String.fromString("(?ui)");
  public static UTF8String lowercaseRegex(final UTF8String regex) {
    return UTF8String.concat(lowercaseRegexPrefix, regex);
  }
  public static UTF8String collationAwareRegex(final UTF8String regex, final int collationId) {
    return supportsLowercaseRegex(collationId) ? lowercaseRegex(regex) : regex;
  }

  /**
   * Other collation-aware expressions.
   */

  // TODO: Add other collation-aware expressions.

  /**
   * Utility class for collation-aware UTF8String operations.
   */

  private static class CollationAwareUTF8String {

    private static boolean lowercaseMatchAt(final UTF8String l, final UTF8String r,
        int pos, int len) {
      if (len + pos > l.numChars() || pos < 0) {
        return false;
      }
      return l.substring(pos, pos + len).toLowerCase().equals(r);
    }

    private static UTF8String replace(final UTF8String src, final UTF8String search,
        final UTF8String replace, final int collationId) {
      // This collation aware implementation is based on existing implementation on UTF8String
      if (src.numBytes() == 0 || search.numBytes() == 0) {
        return src;
      }

      StringSearch stringSearch = CollationFactory.getStringSearch(src, search, collationId);

      // Find the first occurrence of the search string.
      int end = stringSearch.next();
      if (end == StringSearch.DONE) {
        // Search string was not found, so string is unchanged.
        return src;
      }

      // Initialize byte positions
      int c = 0;
      int byteStart = 0; // position in byte
      int byteEnd = 0; // position in byte
      while (byteEnd < src.numBytes() && c < end) {
        byteEnd += UTF8String.numBytesForFirstByte(src.getByte(byteEnd));
        c += 1;
      }

      // At least one match was found. Estimate space needed for result.
      // The 16x multiplier here is chosen to match commons-lang3's implementation.
      int increase = Math.max(0, Math.abs(replace.numBytes() - search.numBytes())) * 16;
      final UTF8StringBuilder buf = new UTF8StringBuilder(src.numBytes() + increase);
      while (end != StringSearch.DONE) {
        buf.appendBytes(src.getBaseObject(), src.getBaseOffset() + byteStart, byteEnd - byteStart);
        buf.append(replace);

        // Move byteStart to the beginning of the current match
        byteStart = byteEnd;
        int cs = c;
        // Move cs to the end of the current match
        // This is necessary because the search string may contain 'multi-character' characters
        while (byteStart < src.numBytes() && cs < c + stringSearch.getMatchLength()) {
          byteStart += UTF8String.numBytesForFirstByte(src.getByte(byteStart));
          cs += 1;
        }
        // Go to next match
        end = stringSearch.next();
        // Update byte positions
        while (byteEnd < src.numBytes() && c < end) {
          byteEnd += UTF8String.numBytesForFirstByte(src.getByte(byteEnd));
          c += 1;
        }
      }
      buf.appendBytes(src.getBaseObject(), src.getBaseOffset() + byteStart,
        src.numBytes() - byteStart);
      return buf.build();
    }

    private static UTF8String lowercaseReplace(final UTF8String src, final UTF8String search,
        final UTF8String replace) {
      if (src.numBytes() == 0 || search.numBytes() == 0) {
        return src;
      }
      UTF8String lowercaseString = src.toLowerCase();
      UTF8String lowercaseSearch = search.toLowerCase();

      int start = 0;
      int end = lowercaseString.indexOf(lowercaseSearch, 0);
      if (end == -1) {
        // Search string was not found, so string is unchanged.
        return src;
      }

      // Initialize byte positions
      int c = 0;
      int byteStart = 0; // position in byte
      int byteEnd = 0; // position in byte
      while (byteEnd < src.numBytes() && c < end) {
        byteEnd += UTF8String.numBytesForFirstByte(src.getByte(byteEnd));
        c += 1;
      }

      // At least one match was found. Estimate space needed for result.
      // The 16x multiplier here is chosen to match commons-lang3's implementation.
      int increase = Math.max(0, replace.numBytes() - search.numBytes()) * 16;
      final UTF8StringBuilder buf = new UTF8StringBuilder(src.numBytes() + increase);
      while (end != -1) {
        buf.appendBytes(src.getBaseObject(), src.getBaseOffset() + byteStart, byteEnd - byteStart);
        buf.append(replace);
        // Update character positions
        start = end + lowercaseSearch.numChars();
        end = lowercaseString.indexOf(lowercaseSearch, start);
        // Update byte positions
        byteStart = byteEnd + search.numBytes();
        while (byteEnd < src.numBytes() && c < end) {
          byteEnd += UTF8String.numBytesForFirstByte(src.getByte(byteEnd));
          c += 1;
        }
      }
      buf.appendBytes(src.getBaseObject(), src.getBaseOffset() + byteStart,
        src.numBytes() - byteStart);
      return buf.build();
    }

    private static String toUpperCase(final String target, final int collationId) {
      ULocale locale = CollationFactory.fetchCollation(collationId)
              .collator.getLocale(ULocale.ACTUAL_LOCALE);
      return UCharacter.toUpperCase(locale, target);
    }

    private static String toLowerCase(final String target, final int collationId) {
      ULocale locale = CollationFactory.fetchCollation(collationId)
              .collator.getLocale(ULocale.ACTUAL_LOCALE);
      return UCharacter.toLowerCase(locale, target);
    }

    private static String toTitleCase(final String target, final int collationId) {
      ULocale locale = CollationFactory.fetchCollation(collationId)
              .collator.getLocale(ULocale.ACTUAL_LOCALE);
      return UCharacter.toTitleCase(locale, target, BreakIterator.getWordInstance(locale));
    }

    private static int findInSet(final UTF8String match, final UTF8String set, int collationId) {
      if (match.contains(UTF8String.fromString(","))) {
        return 0;
      }

      String setString = set.toString();
      StringSearch stringSearch = CollationFactory.getStringSearch(setString, match.toString(),
        collationId);

      int wordStart = 0;
      while ((wordStart = stringSearch.next()) != StringSearch.DONE) {
        boolean isValidStart = wordStart == 0 || setString.charAt(wordStart - 1) == ',';
        boolean isValidEnd = wordStart + stringSearch.getMatchLength() == setString.length()
                || setString.charAt(wordStart + stringSearch.getMatchLength()) == ',';

        if (isValidStart && isValidEnd) {
          int pos = 0;
          for (int i = 0; i < setString.length() && i < wordStart; i++) {
            if (setString.charAt(i) == ',') {
              pos++;
            }
          }

          return pos + 1;
        }
      }

      return 0;
    }

    private static int lowercaseIndexOf(final UTF8String target, final UTF8String pattern,
        final int start) {
      if (pattern.numChars() == 0) return 0;
      int lenHaystack = target.numChars(), lenNeedle = pattern.numChars();
      final UTF8String firstLower = pattern.substring(0, 1).toLowerCase();
      final UTF8String needle = pattern.toLowerCase();
      for (int i = start; i <= (lenHaystack - lenNeedle); i++) {
        if (target.substring(i, i + 1).toLowerCase().equals(firstLower)) {
          if (CollationAwareUTF8String.lowercaseMatchAt(target, needle, i, lenNeedle)) {
            return i;
          }
        }
      }
      return -1;
    }

    private static int indexOf(final UTF8String target, final UTF8String pattern,
        final int start, final int collationId) {
      if (pattern.numBytes() == 0) {
        return 0;
      }

      StringSearch stringSearch = CollationFactory.getStringSearch(target, pattern, collationId);
      stringSearch.setIndex(start);

      return stringSearch.next();
    }

    private static int find(UTF8String target, UTF8String pattern, int start,
        int collationId) {
      assert (pattern.numBytes() > 0);

      StringSearch stringSearch = CollationFactory.getStringSearch(target, pattern, collationId);
      // Set search start position (start from character at start position)
      stringSearch.setIndex(target.bytePosToChar(start));

      // Return either the byte position or -1 if not found
      return target.charPosToByte(stringSearch.next());
    }

    private static UTF8String subStringIndex(final UTF8String string, final UTF8String delimiter,
        int count, final int collationId) {
      if (delimiter.numBytes() == 0 || count == 0 || string.numBytes() == 0) {
        return UTF8String.EMPTY_UTF8;
      }
      if (count > 0) {
        int idx = -1;
        while (count > 0) {
          idx = find(string, delimiter, idx + 1, collationId);
          if (idx >= 0) {
            count --;
          } else {
            // can not find enough delim
            return string;
          }
        }
        if (idx == 0) {
          return UTF8String.EMPTY_UTF8;
        }
        byte[] bytes = new byte[idx];
        copyMemory(string.getBaseObject(), string.getBaseOffset(), bytes, BYTE_ARRAY_OFFSET, idx);
        return UTF8String.fromBytes(bytes);

      } else {
        count = -count;

        StringSearch stringSearch = CollationFactory
          .getStringSearch(string, delimiter, collationId);

        int start = string.numChars() - 1;
        int lastMatchLength = 0;
        int prevStart = -1;
        while (count > 0) {
          stringSearch.reset();
          prevStart = -1;
          int matchStart = stringSearch.next();
          lastMatchLength = stringSearch.getMatchLength();
          while (matchStart <= start) {
            if (matchStart != StringSearch.DONE) {
              // Found a match, update the start position
              prevStart = matchStart;
              matchStart = stringSearch.next();
            } else {
              break;
            }
          }

          if (prevStart == -1) {
            // can not find enough delim
            return string;
          } else {
            start = prevStart - 1;
            count--;
          }
        }

        int resultStart = prevStart + lastMatchLength;
        if (resultStart == string.numChars()) {
          return UTF8String.EMPTY_UTF8;
        }

        return string.substring(resultStart, string.numChars());
      }
    }

    private static UTF8String lowercaseSubStringIndex(final UTF8String string,
      final UTF8String delimiter, int count) {
      if (delimiter.numBytes() == 0 || count == 0) {
        return UTF8String.EMPTY_UTF8;
      }

      UTF8String lowercaseString = string.toLowerCase();
      UTF8String lowercaseDelimiter = delimiter.toLowerCase();

      if (count > 0) {
        int idx = -1;
        while (count > 0) {
          idx = lowercaseString.find(lowercaseDelimiter, idx + 1);
          if (idx >= 0) {
            count --;
          } else {
            // can not find enough delim
            return string;
          }
        }
        if (idx == 0) {
          return UTF8String.EMPTY_UTF8;
        }
        byte[] bytes = new byte[idx];
        copyMemory(string.getBaseObject(), string.getBaseOffset(), bytes, BYTE_ARRAY_OFFSET, idx);
        return UTF8String.fromBytes(bytes);

      } else {
        int idx = string.numBytes() - delimiter.numBytes() + 1;
        count = -count;
        while (count > 0) {
          idx = lowercaseString.rfind(lowercaseDelimiter, idx - 1);
          if (idx >= 0) {
            count --;
          } else {
            // can not find enough delim
            return string;
          }
        }
        if (idx + delimiter.numBytes() == string.numBytes()) {
          return UTF8String.EMPTY_UTF8;
        }
        int size = string.numBytes() - delimiter.numBytes() - idx;
        byte[] bytes = new byte[size];
        copyMemory(string.getBaseObject(), string.getBaseOffset() + idx + delimiter.numBytes(),
                bytes, BYTE_ARRAY_OFFSET, size);
        return UTF8String.fromBytes(bytes);
      }
    }

    private static Map<String, String> getCollationAwareDict(UTF8String string,
        Map<String, String> dict, int collationId) {
      String srcStr = string.toString();

      Map<String, String> collationAwareDict = new HashMap<>();
      for (String key : dict.keySet()) {
        StringSearch stringSearch =
          CollationFactory.getStringSearch(string, UTF8String.fromString(key), collationId);

        int pos = 0;
        while ((pos = stringSearch.next()) != StringSearch.DONE) {
          int codePoint = srcStr.codePointAt(pos);
          int charCount = Character.charCount(codePoint);
          String newKey = srcStr.substring(pos, pos + charCount);

          boolean exists = false;
          for (String existingKey : collationAwareDict.keySet()) {
            if (stringSearch.getCollator().compare(existingKey, newKey) == 0) {
              collationAwareDict.put(newKey, collationAwareDict.get(existingKey));
              exists = true;
              break;
            }
          }

          if (!exists) {
            collationAwareDict.put(newKey, dict.get(key));
          }
        }
      }

      return collationAwareDict;
    }

  }

}
