package catalyst
package rules

import trees._
import util._

abstract class RuleExecutor[TreeType <: TreeNode[_]] extends Logging {

  /**
   * An execution strategy for rules that indicates the maximum number of executions. If the
   * execution reaches fix point (i.e. converge) before maxIterations, it will stop.
   */
  abstract class Strategy { def maxIterations: Int }

  /** A strategy that only runs once. */
  case object Once extends Strategy { val maxIterations = 1 }

  /** A strategy that runs until fix point or maxIterations times, whichever comes first. */
  case class FixedPoint(maxIterations: Int) extends Strategy

  /** A batch of rules. */
  protected case class Batch(name: String, strategy: Strategy, rules: Rule[TreeType]*)

  /** Defines a sequence of rule batches, to be overridden by the implementation. */
  protected val batches: Seq[Batch]

  /**
   * Executes the batches of rules defined by the subclass. The batches are executed serially
   * using the defined execution strategy. Within each batch, rules are also executed serially.
   */
  def apply(plan: TreeType): TreeType = {
    var curPlan = plan

    batches.foreach { batch =>
      var iteration = 1
      var lastPlan = curPlan
      curPlan = batch.rules.foldLeft(curPlan) { case (curPlan, rule) => rule(curPlan) }

      // Run until fix point (or the max number of iterations as specified in the strategy.
      while (iteration < batch.strategy.maxIterations && !curPlan.fastEquals(lastPlan)) {
        lastPlan = curPlan
        curPlan = batch.rules.foldLeft(curPlan) {
          case (curPlan, rule) =>
            val result = rule(curPlan)
            if(!result.fastEquals(curPlan)) {
              logger.debug(
                s"""
                  |=== Applying Rule ${rule.ruleName} ===
                  |${sideBySide(curPlan.treeString, result.treeString).mkString("\n")}
                """.stripMargin)
            }

            result
        }
        iteration += 1
      }
    }

    curPlan
  }
}
