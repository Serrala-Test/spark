package catalyst
package expressions

import types.DoubleType

case object Rand extends LeafExpression {
  def dataType = DoubleType
  def foldable = false
  def nullable = false
  def references = Set.empty
  override def toString = "RAND()"
}