package catalyst
package analysis

import expressions._
import plans.logical._
import rules._

/**
 * A trivial [[Analyzer]] with an [[EmptyCatalog]]. Used for testing when all relations are
 * already filled in and the analyser needs only to resolve attribute references.
 */
object SimpleAnalyzer extends Analyzer(EmptyCatalog)

class Analyzer(catalog: Catalog) extends RuleExecutor[LogicalPlan] {
  val fixedPoint = FixedPoint(100)

  val batches = Seq(
    Batch("Resolution", fixedPoint,
      ResolveReferences,
      ResolveRelations),
    Batch("Aggregation", Once,
      GlobalAggregates)
  )

  /**
   * Replaces [[UnresolvedRelation]]s with concrete relations from the catalog.
   */
  object ResolveRelations extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      case UnresolvedRelation(name, alias) => catalog.lookupRelation(name, alias)
    }
  }

  /**
   * Replaces [[UnresolvedAttribute]]s with concrete [[AttributeReference]]s from a logical plan node's children.
   */
  object ResolveReferences extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      case q: LogicalPlan =>
        // logger.fine(s"resolving ${plan.simpleString}")
        q transformExpressions {
        case u @ UnresolvedAttribute(name) =>
          // Leave unchanged if resolution fails.  Hopefully will be resolved next round.
          q.resolve(name).getOrElse(u)
      }
    }
  }

  /**
   * Turns projections that contain aggregate expressions into aggregations.
   */
  object GlobalAggregates extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      case Project(projectList, child) if containsAggregates(projectList) =>
        Aggregate(Nil, projectList, child)
    }

    def containsAggregates(exprs: Seq[Expression]): Boolean = {
      exprs.foreach(_.foreach {
        case agg: AggregateExpression => return true
        case _ =>
      })
      return false
    }
  }
}