package au.com.dius.pact.core.matchers

import au.com.dius.pact.core.matchers.util.IndicesCombination
import au.com.dius.pact.core.matchers.util.LargestKeyValue
import au.com.dius.pact.core.matchers.util.corresponds
import au.com.dius.pact.core.matchers.util.memoizeFixed
import au.com.dius.pact.core.matchers.util.padTo
import au.com.dius.pact.core.matchers.util.tails
import au.com.dius.pact.core.model.PathToken
import au.com.dius.pact.core.model.matchingrules.ArrayContainsMatcher
import au.com.dius.pact.core.model.matchingrules.EqualsIgnoreOrderMatcher
import au.com.dius.pact.core.model.matchingrules.EqualsMatcher
import au.com.dius.pact.core.model.matchingrules.MatchingRule
import au.com.dius.pact.core.model.matchingrules.MatchingRuleCategory
import au.com.dius.pact.core.model.matchingrules.MatchingRuleGroup
import au.com.dius.pact.core.model.matchingrules.MatchingRules
import au.com.dius.pact.core.model.matchingrules.MaxEqualsIgnoreOrderMatcher
import au.com.dius.pact.core.model.matchingrules.MinEqualsIgnoreOrderMatcher
import au.com.dius.pact.core.model.matchingrules.MinMaxEqualsIgnoreOrderMatcher
import au.com.dius.pact.core.model.matchingrules.TypeMatcher
import au.com.dius.pact.core.model.matchingrules.ValuesMatcher
import au.com.dius.pact.core.model.parsePath
import mu.KLogging
import java.math.BigInteger
import java.util.Comparator
import java.util.function.Predicate

@Suppress("TooManyFunctions")
object Matchers : KLogging() {

  private val intRegex = Regex("\\d+")

  private fun matchesToken(pathElement: String, token: PathToken): Int {
    return when (token) {
      is PathToken.Root -> if (pathElement == "$") 2 else 0
      is PathToken.Field -> if (pathElement == token.name) 2 else 0
      is PathToken.Index -> if (pathElement.matches(intRegex) && token.index == pathElement.toInt()) 2 else 0
      is PathToken.StarIndex -> if (pathElement.matches(intRegex)) 1 else 0
      is PathToken.Star -> 1
      else -> 0
    }
  }

  fun matchesPath(pathExp: String, path: List<String>): Int {
    val parseResult = parsePath(pathExp)
    val filter = tails(path.reversed()).filter { l ->
      corresponds(l.reversed(), parseResult) { pathElement, pathToken ->
        matchesToken(pathElement, pathToken) != 0
      }
    }
    return if (filter.isNotEmpty()) {
      filter.maxByOrNull { seq -> seq.size }?.size ?: 0
    } else {
      0
    }
  }

  fun calculatePathWeight(pathExp: String, path: List<String>): Int {
    val parseResult = parsePath(pathExp)
    return path.zip(parseResult).asSequence().map {
        matchesToken(it.first, it.second)
    }.reduce { acc, i -> acc * i }
  }

  @Deprecated("Use function from MatchingContext or MatchingRuleCategory")
  fun resolveMatchers(
    matchers: MatchingRules,
    category: String,
    items: List<String>,
    pathComparator: Comparator<String>
  ) = when (category) {
    "body" -> matchers.rulesForCategory(category).filter(Predicate {
      matchesPath(it, items) > 0
    })
    "header", "query", "metadata" -> matchers.rulesForCategory(category).filter(Predicate { key ->
      items.all { pathComparator.compare(key, it) == 0 }
    })
    else -> matchers.rulesForCategory(category)
  }

  @Deprecated("Use function from MatchingContext or MatchingRuleCategory")
  @JvmStatic
  @JvmOverloads
  fun matcherDefined(
    category: String,
    path: List<String>,
    matchers: MatchingRules?,
    pathComparator: Comparator<String> = Comparator.naturalOrder()
  ): Boolean =
    if (matchers != null)
      resolveMatchers(matchers, category, path, pathComparator).isNotEmpty()
    else false

  /**
   * Determines if a matcher of the form '[*]' exists for the path
   */
  @Deprecated("Use function from MatchingContext or MatchingRuleCategory")
  @JvmStatic
  fun wildcardIndexMatcherDefined(path: List<String>, category: String, matchers: MatchingRules?) =
    if (matchers != null) {
      val resolvedMatchers = matchers.rulesForCategory(category).filter(Predicate {
        matchesPath(it, path) == path.size
      })
      resolvedMatchers.matchingRules.keys.any { entry -> entry.endsWith("[*]") }
    } else false

  /**
   * Determines if any ignore-order matcher is defined for path or ancestor of path.
   */
  @Deprecated("Use function from MatchingContext or MatchingRuleCategory")
  @JvmStatic
  fun isEqualsIgnoreOrderMatcherDefined(path: List<String>, category: String, matchers: MatchingRules?) =
    if (matchers != null) {
      val matcherDef = selectBestMatcher(matchers, category, path)
      matcherDef.rules.any {
        it is EqualsIgnoreOrderMatcher ||
        it is MinEqualsIgnoreOrderMatcher ||
        it is MaxEqualsIgnoreOrderMatcher ||
        it is MinMaxEqualsIgnoreOrderMatcher
      }
    } else false

  /**
   * Determines if a matcher of the form '.*' exists for the path
   */
  @JvmStatic
  @Deprecated("Use function from MatchingContext or MatchingRuleCategory")
  fun wildcardMatcherDefined(path: List<String>, category: String, matchers: MatchingRules?) =
    if (matchers != null) {
      val resolvedMatchers = matchers.rulesForCategory(category).filter(Predicate {
        matchesPath(it, path) == path.size
      })
      resolvedMatchers.matchingRules.keys.any { entry -> entry.endsWith(".*") }
    } else false

  @JvmStatic
  @JvmOverloads
  fun <M : Mismatch> domatch(
    context: MatchingContext,
    path: List<String>,
    expected: Any?,
    actual: Any?,
    mismatchFn: MismatchFactory<M>,
    pathComparator: Comparator<String> = Comparator.naturalOrder()
  ): List<M> {
    val matcherDef = context.selectBestMatcher(path, pathComparator)
    return domatch(matcherDef, path, expected, actual, mismatchFn)
  }

  @Deprecated("Use function from MatchingContext or MatchingRuleCategory")
  @JvmStatic
  @JvmOverloads
  fun selectBestMatcher(
    matchers: MatchingRules,
    category: String,
    path: List<String>,
    pathComparator: Comparator<String> = Comparator.naturalOrder()
  ): MatchingRuleGroup {
    val matcherCategory = resolveMatchers(matchers, category, path, pathComparator)
    return if (category == "body") {
      val result = matcherCategory.maxBy(Comparator { a, b ->
        val weightA = calculatePathWeight(a, path)
        val weightB = calculatePathWeight(b, path)
        when {
          weightA == weightB -> when {
            a.length > b.length -> 1
            a.length < b.length -> -1
            else -> 0
          }
          weightA > weightB -> 1
          else -> -1
        }
      })
      result?.second?.copy(cascaded = parsePath(result.first).size != path.size) ?: MatchingRuleGroup()
    } else {
      matcherCategory.matchingRules.values.first()
    }
  }

  @Deprecated("Use function from MatchingContext or MatchingRuleCategory")
  fun typeMatcherDefined(category: String, path: List<String>, matchingRules: MatchingRules): Boolean {
    val resolvedMatchers = resolveMatchers(matchingRules, category, path, Comparator.naturalOrder())
    return resolvedMatchers.allMatchingRules().any { it is TypeMatcher }
  }

  /**
   * Compares the expected and actual maps using the provided matching rule
   */
  fun <T> compareMaps(
    path: List<String>,
    matcher: MatchingRule,
    expectedEntries: Map<String, T>,
    actualEntries: Map<String, T>,
    context: MatchingContext,
    generateDiff: () -> String,
    callback: (List<String>, T?, T?) -> List<BodyItemMatchResult>
  ): List<BodyItemMatchResult> {
    val result = mutableListOf<BodyItemMatchResult>()
    if (matcher is ValuesMatcher) {
      actualEntries.entries.forEach { (key, value) ->
        if (expectedEntries.containsKey(key)) {
          result.addAll(callback(path + key, expectedEntries[key]!!, value))
        } else {
          result.addAll(callback(path + key, expectedEntries.values.firstOrNull(), value))
        }
      }
    } else {
      result.addAll(context.matchKeys(path, expectedEntries, actualEntries, generateDiff))
      expectedEntries.entries.forEach { (key, value) ->
        if (actualEntries.containsKey(key)) {
          result.addAll(callback(path + key, value, actualEntries[key]))
        }
      }
    }
    return result
  }

  @Suppress("LongMethod")
  fun <T> compareLists(
    path: List<String>,
    matcher: MatchingRule,
    expectedList: List<T>,
    actualList: List<T>,
    context: MatchingContext,
    generateDiff: () -> String,
    cascaded: Boolean,
    callback: (List<String>, T, T, MatchingContext) -> List<BodyItemMatchResult>
  ): List<BodyItemMatchResult> {
    val result = mutableListOf<BodyItemMatchResult>()
    val matchResult = domatch(matcher, path, expectedList, actualList, BodyMismatchFactory, cascaded)
    if (matchResult.isNotEmpty()) {
      result.add(BodyItemMatchResult(path.joinToString("."), matchResult))
    }
    if (expectedList.isNotEmpty()) {
      when (matcher) {
        is EqualsIgnoreOrderMatcher,
        is MinEqualsIgnoreOrderMatcher,
        is MaxEqualsIgnoreOrderMatcher,
        is MinMaxEqualsIgnoreOrderMatcher -> {
          // match unordered list
          logger.debug { "compareLists: ignore-order matcher defined for path $path" }
          // No need to pad 'expected' as we already visit all 'actual' values
          result.addAll(compareListContentUnordered(expectedList, actualList, path, context, generateDiff, callback))
        }
        is ArrayContainsMatcher -> {
          val variants = matcher.variants.ifEmpty {
            expectedList.mapIndexed { index, _ ->
              Triple(
                index,
                MatchingRuleCategory("body", mutableMapOf("" to MatchingRuleGroup(mutableListOf(EqualsMatcher)))),
                emptyMap()
              )
            }
          }
          for ((index, variant) in variants.withIndex()) {
            if (index < expectedList.size) {
              val expectedValue = expectedList[index]
              val newContext = MatchingContext(variant.second, context.allowUnexpectedKeys)
              val noneMatched = actualList.withIndex().all { (actualIndex, value) ->
                val variantResult = callback(listOf("$"), expectedValue, value, newContext)
                val mismatches = variantResult.flatMap { it.result }
                logger.debug {
                  "Comparing list item $actualIndex with value '$value' to '$expectedValue' -> " +
                    "${mismatches.size} mismatches"
                }
                mismatches.isNotEmpty()
              }
              if (noneMatched) {
                result.add(BodyItemMatchResult(path.joinToString("."),
                  listOf(BodyMismatch(expectedValue, actualList,
                    "Variant at index $index ($expectedValue) was not found in the actual list",
                    path.joinToString("."), generateDiff()
                  ))
                ))
              }
            } else {
              result.add(BodyItemMatchResult(path.joinToString("."),
                listOf(BodyMismatch(expectedList, actualList,
                  "ArrayContains: variant $index is missing from the expected list, which has " +
                    "${expectedList.size} items", path.joinToString("."), generateDiff()
                ))
              ))
            }
          }
        }
        else -> {
          result.addAll(compareListContent(expectedList.padTo(actualList.size, expectedList.first()),
            actualList, path, context, generateDiff, callback))
        }
      }
    }
    return result
  }

  /**
   * Compares any "extra" actual elements to expected
   */
  private fun <T> compareActualElements(
    path: List<String>,
    actualIndex: Int,
    expectedValues: List<T>,
    actual: T,
    context: MatchingContext,
    callback: (List<String>, T, T, MatchingContext) -> List<BodyItemMatchResult>
  ): List<BodyItemMatchResult> {
    val indexPath = path + actualIndex.toString()
    return if (context.directMatcherDefined(indexPath)) {
      callback(indexPath, expectedValues[0], actual, context)
    } else {
      emptyList()
    }
  }

  /**
   * Compares every permutation of actual against expected.
   */
  fun <T> compareListContentUnordered(
    expectedList: List<T>,
    actualList: List<T>,
    path: List<String>,
    context: MatchingContext,
    generateDiff: () -> String,
    callback: (List<String>, T, T, MatchingContext) -> List<BodyItemMatchResult>
  ): List<BodyItemMatchResult> {
    val memoizedActualCompare = { actualIndex: Int ->
      compareActualElements(path, actualIndex, expectedList, actualList[actualIndex], context, callback)
    }.memoizeFixed(actualList.size)

    val memoizedCompare = { expectedIndex: Int, actualIndex: Int ->
      callback(path + expectedIndex.toString(), expectedList[expectedIndex], actualList[actualIndex], context)
    }.memoizeFixed(expectedList.size, actualList.size)

    val longestMatch = LargestKeyValue<Int, IndicesCombination>()
    val examinedActualIndicesCombos = HashSet<BigInteger>()
    /**
     * Determines if a matching permutation exists.
     *
     * Note: this algorithm seems to have a worst case O(n*2^n) time and O(2^n) space complexity
     * if a lot of the actuals match a lot of the expected (e.g., if expected uses regex matching
     * with something like [3|2|1, 2|1, 1]). Without the caching, the time complexity jumps to
     * around O(2^2n). Caching/memoization is also used above for compare, to effectively achieve
     * just O(n^2) calls instead of O(2^n).
     *
     * For most normal cases, average performance should be closer to O(n^2) time and O(n) space
     * complexity if there aren't many duplicate matches. Best case is O(n)/O(n) if its already
     * in order.
     *
     * @param expectedIndex index of expected being compared against
     * @param actualIndices combination of remaining actual indices to compare
     */
    fun hasMatchingPermutation(
      expectedIndex: Int = 0,
      actualIndices: IndicesCombination = IndicesCombination.of(actualList.size)
    ): Boolean {
      return if (actualIndices.comboId in examinedActualIndicesCombos) {
        false
      } else {
        examinedActualIndicesCombos.add(actualIndices.comboId)
        longestMatch.useIfLarger(expectedIndex, actualIndices)
        when {
          expectedIndex < expectedList.size -> {
            actualIndices.indices().any { actualIndex ->
              memoizedCompare(expectedIndex, actualIndex).all {
                it.result.isEmpty()
              } && hasMatchingPermutation(expectedIndex + 1, actualIndices - actualIndex)
            }
          }
          actualList.size > expectedList.size -> {
            actualIndices.indices().all { actualIndex ->
              memoizedActualCompare(actualIndex).all {
                it.result.isEmpty()
              }
            }
          }
          else -> true
        }
      }
    }

    return if (hasMatchingPermutation()) {
      emptyList()
    } else {
      val smallestCombo = longestMatch.value ?: IndicesCombination.of(actualList.size)
      val longestMatch = longestMatch.key ?: 0

      val remainingErrors = smallestCombo.indices().map { actualIndex ->
        (longestMatch until expectedList.size).map { expectedIndex ->
          memoizedCompare(expectedIndex, actualIndex).flatMap { it.result }
        }.flatten() + if (actualList.size > expectedList.size) {
          memoizedActualCompare(actualIndex).flatMap { it.result }
        } else emptyList()
      }.toList().flatten()
        .groupBy { it.path }
        .map { (path, mismatches) -> BodyItemMatchResult(path, mismatches) }

      listOf(BodyItemMatchResult(path.joinToString("."),
        listOf(BodyMismatch(expectedList, actualList,
          "Expected $expectedList to match $actualList ignoring order of elements",
          path.joinToString("."), generateDiff()
        ))
      )) + remainingErrors
    }
  }

  fun <T> compareListContent(
    expectedList: List<T>,
    actualList: List<T>,
    path: List<String>,
    context: MatchingContext,
    generateDiff: () -> String,
    callback: (List<String>, T, T, MatchingContext) -> List<BodyItemMatchResult>
  ): List<BodyItemMatchResult> {
    val result = mutableListOf<BodyItemMatchResult>()
    for ((index, value) in expectedList.withIndex()) {
      if (index < actualList.size) {
        result.addAll(callback(path + index.toString(), value, actualList[index], context))
      } else if (!context.matcherDefined(path)) {
        result.add(BodyItemMatchResult(path.joinToString("."),
          listOf(BodyMismatch(expectedList, actualList,
            "Expected $value but was missing",
            path.joinToString("."), generateDiff()))))
      }
    }
    return result
  }
}
