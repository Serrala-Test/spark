package catalyst
package analysis

import execution.MetastoreRelation
import expressions._
import plans.logical._
import rules._

/**
 * A trivial [[catalyst.analysis.Analyzer Analyzer]] with an
 * [[catalyst.analysis.EmptyCatalog EmptyCatalog]] and
 * [[catalyst.analysis.EmptyFunctionRegistry EmptyFunctionRegistry]]. Used for testing
 * when all relations are already filled in and the analyser needs only to resolve attribute
 * references.
 */
object SimpleAnalyzer extends Analyzer(EmptyCatalog, EmptyFunctionRegistry, true)

/**
 * Provides a logical query plan analyzer, which translates
 * [[catalyst.analysis.UnresolvedAttribute UnresolvedAttribute]]s and
 * [[catalyst.analysis.UnresolvedRelation UnresolvedRelation]]s into fully typed objects using
 * information in a schema [[catalyst.analysis.Catalog Catalog]] and a
 * [[catalyst.analysis.FunctionRegistry]].
 */
class Analyzer(catalog: Catalog, registry: FunctionRegistry, caseSensitive: Boolean)
  extends RuleExecutor[LogicalPlan] with HiveTypeCoercion {

  // TODO: pass this in as a parameter.
  val fixedPoint = FixedPoint(100)

  val batches: Seq[Batch] = Seq(
    Batch("LocalRelations", Once,
      NewLocalRelationInstances),
    Batch("CaseInsensitiveAttributeReferences", Once,
      (if (caseSensitive) Nil else LowercaseAttributeReferences :: Nil) : _*),
    Batch("Resolution", fixedPoint,
      ResolveReferences ::
      ResolveRelations ::
      StarExpansion ::
      ResolveFunctions ::
      GlobalAggregates ::
      PreInsertionCasts ::
      typeCoercionRules :_*)
  )

  /**
   * Replaces [[catalyst.analysis.UnresolvedRelation UnresolvedRelation]]s with concrete relations
   * from the catalog.
   */
  object ResolveRelations extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      case UnresolvedRelation(name, alias) => catalog.lookupRelation(name, alias)
    }
  }

  /**
   * Makes attribute naming case insensitive by turning all UnresolvedAttributes to lowercase.
   */
  object LowercaseAttributeReferences extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      case UnresolvedRelation(name, alias) => UnresolvedRelation(name, alias.map(_.toLowerCase))
      case Subquery(alias, child) => Subquery(alias.toLowerCase, child)
      case q: LogicalPlan => q transformExpressions {
        case s: Star => s.copy(table = s.table.map(_.toLowerCase))
        case UnresolvedAttribute(name) => UnresolvedAttribute(name.toLowerCase)
        case Alias(c, name) => Alias(c, name.toLowerCase)()
      }
    }
  }

  /**
   * Replaces [[catalyst.analysis.UnresolvedAttribute UnresolvedAttribute]]s with concrete
   * [[catalyst.expressions.AttributeReference AttributeReferences]] from a logical plan node's
   * children.
   */
  object ResolveReferences extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transformUp {
      case q: LogicalPlan if q.childrenResolved =>
        logger.trace(s"Attempting to resolve ${q.simpleString}")
        q transformExpressions {
          case u @ UnresolvedAttribute(name) =>
            // Leave unchanged if resolution fails.  Hopefully will be resolved next round.
            val result = q.resolve(name).getOrElse(u)
            logger.debug(s"Resolving $u to $result")
            result
        }
    }
  }

  /**
   * Replaces [[catalyst.analysis.UnresolvedFunction UnresolvedFunction]]s with concrete
   * [[catalyst.expressions.Expression Expressions]].
   */
  object ResolveFunctions extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      case q: LogicalPlan =>
        q transformExpressions {
          case u @ UnresolvedFunction(name, children) if u.childrenResolved =>
            registry.lookupFunction(name, children)
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
      false
    }
  }

  /**
   * Expands any references to [[catalyst.analysis.Star Star]] (*) in project operators.
   */
  object StarExpansion extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transform {
      // Wait until children are resolved
      case p: LogicalPlan if !p.childrenResolved => p
      // If the projection list contains Stars, expand it.
      case p @ Project(projectList, child) if containsStar(projectList) =>
        Project(
          projectList.flatMap {
            case s: Star => s.expand(child.output)
            case o => o :: Nil
          },
          child)
      // If the aggregate function argument contains Stars, expand it.
      case a: Aggregate if containsStar(a.aggregateExpressions) =>
        a.copy(
          aggregateExpressions = a.aggregateExpressions.flatMap {
            case s: Star => s.expand(a.child.output)
            case o => o :: Nil
          }
        )
    }

    /**
     * Returns true if `exprs` contains a [[catalyst.analysis.Star Star]].
     */
    protected def containsStar(exprs: Seq[NamedExpression]): Boolean =
      exprs.collect { case _: Star => true }.nonEmpty
  }

  /**
   * Casts input data to correct data types according to table definition before inserting into
   * that table.
   */
  object PreInsertionCasts extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.transform {
      // Wait until children are resolved
      case p: LogicalPlan if !p.childrenResolved => p

      case p @ InsertIntoTable(table: MetastoreRelation, partition, child) =>
        val childOutputDataTypes = child.output.map(_.dataType)
        val tableOutputDataTypes = table.output.map(_.dataType)

        // Only do the casting when child output data types differ from table output data types.
        if (childOutputDataTypes sameElements tableOutputDataTypes) {
          p
        } else {
          val castedChildOutput = child.output.zip(tableOutputDataTypes).map {
            case (a, dataType) => Alias(Cast(a, dataType), a.name)()
          }
          p.copy(table, partition, Project(castedChildOutput, child))
        }
    }
  }
}
