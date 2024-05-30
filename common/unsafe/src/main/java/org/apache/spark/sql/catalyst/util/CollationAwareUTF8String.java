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

import static org.apache.spark.unsafe.Platform.BYTE_ARRAY_OFFSET;
import static org.apache.spark.unsafe.Platform.copyMemory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

/**
 * Utility class for collation-aware UTF8String operations.
 */
public class CollationAwareUTF8String {

  /**
   * The constant value to indicate that the match is not found when searching for a pattern
   * string in a target string.
   */
  private static final int MATCH_NOT_FOUND = -1;

  /**
   * Returns whether the target string starts with the specified prefix, starting from the
   * specified position (0-based index referring to character position in UTF8String), with respect
   * to the UTF8_BINARY_LCASE collation. The method assumes that the prefix is already lowercased
   * prior to method call to avoid the overhead of calling .toLowerCase() multiple times on the
   * same prefix string.
   *
   * @param target the string to be searched in
   * @param lowercasePattern the string to be searched for
   * @param startPos the start position for searching (in the target string)
   * @return whether the target string starts with the specified prefix in UTF8_BINARY_LCASE
   */
  public static boolean lowercaseMatchFrom(
      final UTF8String target,
      final UTF8String lowercasePattern,
      int startPos) {
    return lowercaseMatchLengthFrom(target, lowercasePattern, startPos) != MATCH_NOT_FOUND;
  }

  /**
   * Returns the length of the substring of the target string that starts with the specified
   * prefix, starting from the specified position (0-based index referring to character position
   * in UTF8String), with respect to the UTF8_BINARY_LCASE collation. The method assumes that the
   * prefix is already lowercased. The method only considers the part of target string that
   * starts from the specified (inclusive) position (that is, the method does not look at UTF8
   * characters of the target string at or after position `endPos`). If the prefix is not found,
   * MATCH_NOT_FOUND is returned.
   *
   * @param target the string to be searched in
   * @param lowercasePattern the string to be searched for
   * @param startPos the start position for searching (in the target string)
   * @return length of the target substring that starts with the specified prefix in lowercase
   */
  private static int lowercaseMatchLengthFrom(
      final UTF8String target,
      final UTF8String lowercasePattern,
      int startPos) {
    assert startPos >= 0;
    for (int len = 0; len <= target.numChars() - startPos; ++len) {
      if (target.substring(startPos, startPos + len).toLowerCase().equals(lowercasePattern)) {
        return len;
      }
    }
    return MATCH_NOT_FOUND;
  }

  /**
   * Returns the position of the first occurrence of the pattern string in the target string,
   * starting from the specified position (0-based index referring to character position in
   * UTF8String), with respect to the UTF8_BINARY_LCASE collation. The method assumes that the
   * pattern string is already lowercased prior to call. If the pattern is not found,
   * MATCH_NOT_FOUND is returned.
   *
   * @param target the string to be searched in
   * @param lowercasePattern the string to be searched for
   * @param startPos the start position for searching (in the target string)
   * @return the position of the first occurrence of pattern in target
   */
  private static int lowercaseFind(
      final UTF8String target,
      final UTF8String lowercasePattern,
      int startPos) {
    assert startPos >= 0;
    for (int i = startPos; i <= target.numChars(); ++i) {
      if (lowercaseMatchFrom(target, lowercasePattern, i)) {
        return i;
      }
    }
    return MATCH_NOT_FOUND;
  }

  /**
   * Returns whether the target string ends with the specified suffix, ending at the specified
   * position (0-based index referring to character position in UTF8String), with respect to the
   * UTF8_BINARY_LCASE collation. The method assumes that the suffix is already lowercased prior
   * to method call to avoid the overhead of calling .toLowerCase() multiple times on the same
   * suffix string.
   *
   * @param target the string to be searched in
   * @param lowercasePattern the string to be searched for
   * @param endPos the end position for searching (in the target string)
   * @return whether the target string ends with the specified suffix in lowercase
   */
  public static boolean lowercaseMatchUntil(
      final UTF8String target,
      final UTF8String lowercasePattern,
      int endPos) {
    return lowercaseMatchLengthUntil(target, lowercasePattern, endPos) != MATCH_NOT_FOUND;
  }

  /**
   * Returns the length of the substring of the target string that ends with the specified
   * suffix, ending at the specified position (0-based index referring to character position in
   * UTF8String), with respect to the UTF8_BINARY_LCASE collation. The method assumes that the
   * suffix is already lowercased. The method only considers the part of target string that ends
   * at the specified (non-inclusive) position (that is, the method does not look at UTF8
   * characters of the target string at or after position `endPos`). If the suffix is not found,
   * MATCH_NOT_FOUND is returned.
   *
   * @param target the string to be searched in
   * @param lowercasePattern the string to be searched for
   * @param endPos the end position for searching (in the target string)
   * @return length of the target substring that ends with the specified suffix in lowercase
   */
  private static int lowercaseMatchLengthUntil(
      final UTF8String target,
      final UTF8String lowercasePattern,
      int endPos) {
    assert endPos <= target.numChars();
    for (int len = 0; len <= endPos; ++len) {
      if (target.substring(endPos - len, endPos).toLowerCase().equals(lowercasePattern)) {
        return len;
      }
    }
    return MATCH_NOT_FOUND;
  }

  /**
   * Returns the position of the last occurrence of the pattern string in the target string,
   * ending at the specified position (0-based index referring to character position in
   * UTF8String), with respect to the UTF8_BINARY_LCASE collation. The method assumes that the
   * pattern string is already lowercased prior to call. If the pattern is not found,
   * MATCH_NOT_FOUND is returned.
   *
   * @param target the string to be searched in
   * @param lowercasePattern the string to be searched for
   * @param endPos the end position for searching (in the target string)
   * @return the position of the last occurrence of pattern in target
   */
  private static int lowercaseRFind(
      final UTF8String target,
      final UTF8String lowercasePattern,
      int endPos) {
    assert endPos <= target.numChars();
    for (int i = endPos; i >= 0; --i) {
      if (lowercaseMatchUntil(target, lowercasePattern, i)) {
        return i;
      }
    }
    return MATCH_NOT_FOUND;
  }

  public static UTF8String replace(final UTF8String src, final UTF8String search,
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

  public static UTF8String lowercaseReplace(final UTF8String src, final UTF8String search,
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

  public static String toUpperCase(final String target, final int collationId) {
    ULocale locale = CollationFactory.fetchCollation(collationId)
      .collator.getLocale(ULocale.ACTUAL_LOCALE);
    return UCharacter.toUpperCase(locale, target);
  }

  public static String toLowerCase(final String target, final int collationId) {
    ULocale locale = CollationFactory.fetchCollation(collationId)
      .collator.getLocale(ULocale.ACTUAL_LOCALE);
    return UCharacter.toLowerCase(locale, target);
  }

  public static String toTitleCase(final String target, final int collationId) {
    ULocale locale = CollationFactory.fetchCollation(collationId)
      .collator.getLocale(ULocale.ACTUAL_LOCALE);
    return UCharacter.toTitleCase(locale, target, BreakIterator.getWordInstance(locale));
  }

  public static int findInSet(final UTF8String match, final UTF8String set, int collationId) {
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

  /**
   * Returns the position of the first occurrence of the pattern string in the target string,
   * starting from the specified position (0-based index referring to character position in
   * UTF8String), with respect to the UTF8_BINARY_LCASE collation. If the pattern is not found,
   * MATCH_NOT_FOUND is returned.
   *
   * @param target the string to be searched in
   * @param pattern the string to be searched for
   * @param start the start position for searching (in the target string)
   * @return the position of the first occurrence of pattern in target
   */
  public static int lowercaseIndexOf(final UTF8String target, final UTF8String pattern,
      final int start) {
    if (pattern.numChars() == 0) return 0;
    return lowercaseFind(target, pattern.toLowerCase(), start);
  }

  public static int indexOf(final UTF8String target, final UTF8String pattern,
      final int start, final int collationId) {
    if (pattern.numBytes() == 0) {
      return 0;
    }

    StringSearch stringSearch = CollationFactory.getStringSearch(target, pattern, collationId);
    stringSearch.setIndex(start);

    return stringSearch.next();
  }

  public static int find(UTF8String target, UTF8String pattern, int start,
      int collationId) {
    assert (pattern.numBytes() > 0);

    StringSearch stringSearch = CollationFactory.getStringSearch(target, pattern, collationId);
    // Set search start position (start from character at start position)
    stringSearch.setIndex(target.bytePosToChar(start));

    // Return either the byte position or -1 if not found
    return target.charPosToByte(stringSearch.next());
  }

  public static UTF8String subStringIndex(final UTF8String string, final UTF8String delimiter,
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

  public static UTF8String lowercaseSubStringIndex(final UTF8String string,
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
          count--;
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
          count--;
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

  public static Map<String, String> getCollationAwareDict(UTF8String string,
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

  public static UTF8String lowercaseTrim(
      final UTF8String srcString,
      final UTF8String trimString) {
    return lowercaseTrimRight(lowercaseTrimLeft(srcString, trimString), trimString);
  }

  public static UTF8String trim(
      final UTF8String srcString,
      final UTF8String trimString,
      final int collationId) {
    return trimRight(trimLeft(srcString, trimString, collationId), trimString, collationId);
  }

  public static UTF8String lowercaseTrimLeft(
      final UTF8String srcString,
      final UTF8String trimString) {
    // Matching UTF8String behavior for null `trimString`.
    if (trimString == null) {
      return null;
    }

    HashSet<Integer> trimChars = new HashSet<>();
    Iterator<Integer> trimIter = trimString.codePointIterator();
    while (trimIter.hasNext()) trimChars.add(UCharacter.toLowerCase(trimIter.next()));

    int searchIndex = 0;
    Iterator<Integer> srcIter = srcString.codePointIterator();
    while (srcIter.hasNext()) {
      if (!trimChars.contains(UCharacter.toLowerCase(srcIter.next()))) break;
      ++searchIndex;
    }

    return srcString.substring(searchIndex, srcString.numChars());
  }

  public static UTF8String trimLeft(
      final UTF8String srcString,
      final UTF8String trimString,
      final int collationId) {
    // Matching UTF8String behavior for null `trimString`.
    if (trimString == null) {
      return null;
    }

    // Create a set of collation keys for all characters of the trim string, for fast lookup.
    String trim = trimString.toString();
    HashSet<String> trimChars = new HashSet<>();
    for (int i = 0; i < trim.length(); i++) {
      trimChars.add(CollationFactory.getCollationKey(String.valueOf(trim.charAt(i)), collationId));
    }

    // Iterate over srcString from the left and find the first character that is not in trimChars.
    String input = srcString.toString();
    int i = 0;
    while (i < input.length()) {
      String key = CollationFactory.getCollationKey(String.valueOf(input.charAt(i)), collationId);
      if (!trimChars.contains(key)) break;
      ++i;
    }
    // Return the substring from that position to the end of the string.
    return UTF8String.fromString(input.substring(i, srcString.numChars()));
  }

  public static UTF8String lowercaseTrimRight(
      final UTF8String srcString,
      final UTF8String trimString) {
    // Matching UTF8String behavior for null `trimString`.
    if (trimString == null) {
      return null;
    }

    HashSet<Integer> trimChars = new HashSet<>();
    Iterator<Integer> trimIter = trimString.codePointIterator();
    while (trimIter.hasNext()) trimChars.add(UCharacter.toLowerCase(trimIter.next()));

    int searchIndex = srcString.numChars();
    Iterator<Integer> srcIter = srcString.reverseCodePointIterator();
    while (srcIter.hasNext()) {
      if (!trimChars.contains(UCharacter.toLowerCase(srcIter.next()))) {
        break;
      }
      --searchIndex;
    }

    return srcString.substring(0, searchIndex);
  }

  public static UTF8String trimRight(
      final UTF8String srcString,
      final UTF8String trimString,
      final int collationId) {
    // Matching UTF8String behavior for null `trimString`.
    if (trimString == null) {
      return null;
    }

    // Create a set of collation keys for all characters of the trim string, for fast lookup.
    String trim = trimString.toString();
    HashSet<String> trimChars = new HashSet<>();
    for (int i = 0; i < trim.length(); i++) {
      trimChars.add(CollationFactory.getCollationKey(String.valueOf(trim.charAt(i)), collationId));
    }

    // Iterate over srcString from the right and find the first character that is not in trimChars.
    String input = srcString.toString();
    int i = input.length() - 1;
    while (i >= 0) {
      String key = CollationFactory.getCollationKey(String.valueOf(input.charAt(i)), collationId);
      if (!trimChars.contains(key)) break;
      --i;
    }
    // Return the substring from the start of the string until that position.
    return UTF8String.fromString(input.substring(0, i + 1));
  }

  // TODO: Add more collation-aware UTF8String operations here.

}
