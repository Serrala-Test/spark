package catalyst
package expressions

import types.DataType

case class Cast(child: Expression, dataType: DataType) extends UnaryExpression {
  def nullable = child.nullable
  override def toString = s"CAST($child, $dataType)"
}