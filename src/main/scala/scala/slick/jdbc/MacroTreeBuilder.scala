package scala.slick.jdbc

import scala.language.experimental.macros

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.reflect.macros.Context

/** AST builder used by the SQL interpolation macros. */
private[jdbc] class MacroTreeBuilder[C <: Context](val c: C)(paramsList: List[C#Expr[Any]]) {
  import c.universe._

  def abort(msg: String) = c.abort(c.enclosingPosition, msg)

  // create a list of strings passed to this interpolation
  lazy val rawQueryParts: List[String] = {
    //Deconstruct macro application to determine the passed string and the actual parameters
    val Apply(Select(Apply(_, List(Apply(_, strArg))), _), paramList) = c.macroApplication
    strArg map {
      case Literal(Constant(x: String)) => x
      case _ => abort("The interpolation contained something other than constants...")
    }
  }

  /**
   * Create a Tree of the static name of a class
   * eg java.lang.String becomes
   * Select(Select(Select(Ident(nme.ROOTPKG), "java"), "lang"), "String")
   */
  private def createClassTreeFromString(classString: String, generator: String => Name): Tree = {
    val tokens = classString.split('.').toList
    val packages = tokens.dropRight(1) map (newTermName(_))
    val classType = generator(tokens.last)
    val firstPackage = Ident(nme.ROOTPKG)
    val others = (packages :+ classType)
    others.foldLeft[Tree](firstPackage)((prev, elem) => {
      Select(prev, elem)
    })
  }

  /**
   * Creates a tree equivalent to an implicity resolution of a given type
   * eg for type GetResult[Int], this function gives the tree equivalent of
   * scala.Predef.implicitly[GetResult[Int]]
   */
  def implicitTree(reqType: Tree, baseType: Tree) = TypeApply(
    ImplicitlyTree, List(AppliedTypeTree(baseType, List(reqType)))
  )

  //Some commonly used trees that are created on demand
  lazy val GetResultTypeTree = createClassTreeFromString("scala.slick.jdbc.GetResult", newTypeName(_))
  lazy val SetParameterTypeTree = createClassTreeFromString("scala.slick.jdbc.SetParameter", newTypeName(_))
  lazy val TypedStaticQueryTypeTree = createClassTreeFromString("scala.slick.jdbc.TypedStaticQuery", newTypeName(_))
  lazy val GetResultTree = createClassTreeFromString("scala.slick.jdbc.GetResult", newTermName(_))
  lazy val SetParameterTree = createClassTreeFromString("scala.slick.jdbc.SetParameter", newTermName(_))
  lazy val ImplicitlyTree = createClassTreeFromString("scala.Predef.implicitly", newTermName(_))
  lazy val HeterogenousTree = createClassTreeFromString("scala.slick.collection.heterogenous", newTermName(_))
  lazy val VectorTree = createClassTreeFromString("scala.collection.immutable.Vector", newTermName(_))
  lazy val GetNoResultTree = createClassTreeFromString("scala.slick.jdbc.TypedStaticQuery.GetNoResult", newTermName(_))

  /**
   * Creates the tree for GetResult[] of the tsql macro
   */
  def rconvTree(resultTypes: Vector[ClassTag[_]]) = {
    val resultTypeTrees = resultTypes.map (_.runtimeClass.getCanonicalName match {
      case "int" => TypeTree(typeOf[Int])
      case "byte" => TypeTree(typeOf[Byte])
      case "long" => TypeTree(typeOf[Long])
      case "short" => TypeTree(typeOf[Short])
      case "float" => TypeTree(typeOf[Float])
      case "double" => TypeTree(typeOf[Double])
      case "boolean" => TypeTree(typeOf[Boolean])
      case x => TypeTree(c.mirror.staticClass(x).selfType)
    })

    resultTypes.size match {
      case 0 => implicitTree(TypeTree(typeOf[Int]) , GetResultTypeTree)
      case 1 => implicitTree(resultTypeTrees(0), GetResultTypeTree)
      case n if (n <= 22) =>
        implicitTree(AppliedTypeTree(
          Select(Select(Ident(nme.ROOTPKG), newTermName("scala")), newTypeName("Tuple" + resultTypes.size)),
          resultTypeTrees.toList
        ), GetResultTypeTree)
      case n =>
        val rtypeTree = {
          val zero = TypeTree(typeOf[scala.slick.collection.heterogenous.syntax.HNil])
          val :: = Select(Select(HeterogenousTree, newTermName("syntax")), newTypeName("$colon$colon"))
          resultTypeTrees.foldRight[TypTree](zero) { (typ, prev) =>
            AppliedTypeTree(::, List(typ, prev))
          }
        }
        val zero = Select(HeterogenousTree, newTermName("HNil"))
        val zipped = (0 until n) zip resultTypeTrees
        val << = Select(Ident(newTermName("p")), newTermName("$less$less"))
        Apply(
          TypeApply(
            Select(GetResultTree, newTermName("apply")),
            List(rtypeTree)
          ),
          List(
            Function(
              List(ValDef(Modifiers(Flag.PARAM), newTermName("p"), TypeTree(), EmptyTree)),
              Block(
                zipped.map { tup =>
                  val (i: Int, typ: Tree) = tup
                  ValDef(Modifiers(), newTermName("gr" + i), TypeTree(), implicitTree(typ, GetResultTypeTree))
                }.toList,
                zipped.foldRight[Tree](zero) { (tup, prev) =>
                  val (i: Int, typ: Tree) = tup
                  Block(
                    List(ValDef(Modifiers(), newTermName("pv" + i), TypeTree(), Apply(<<, List(Ident(newTermName("gr" + i)))))),
                    Apply(Select(prev, newTermName("$colon$colon")), List(Ident(newTermName("pv" + i))))
                  )
                }
              )
            )
          )
        )
    }
  }

  /**
   * Processing of the query to fill in constants and prepare
   * the query to be used and a list of SetParameter[]
   */
  private lazy val interpolationResultParams: (List[Tree], Tree) = {
    def decode(s: String): (String, Boolean) = {
      if(s.endsWith("##")) {
        val (str, bool) = decode(s.substring(0, s.length-2))
        (str + "#", bool)
      } else if(s.endsWith("#")) {
        (s.substring(0, s.length-1), true)
      } else {
        (s, false)
      }
    }

    /** Fuse adjacent string literals */
    def fuse(l: List[Tree]): List[Tree] = l match {
      case Literal(Constant(s1: String)) :: Literal(Constant(s2: String)) :: ss => fuse(Literal(Constant(s1 + s2)) :: ss)
      case s :: ss => s :: fuse(ss)
      case Nil => Nil
    }

    if(rawQueryParts.length == 1)
      (List(Literal(Constant(rawQueryParts.head))), Select(SetParameterTree, newTermName("SetUnit")))
    else {
      val queryString = new ListBuffer[Tree]
      val remaining = new ListBuffer[c.Expr[SetParameter[Unit]]]
      paramsList.asInstanceOf[List[c.Expr[Any]]].iterator.zip(rawQueryParts.iterator).foreach { case (param, rawQueryPart) =>
        val (queryPart, append) = decode(rawQueryPart)
        queryString.append(Literal(Constant(queryPart)))
        if(append) queryString.append(param.tree)
        else {
          queryString.append(Literal(Constant("?")))
          remaining += c.Expr[SetParameter[Unit]] {
            Apply(
              Select(
                implicitTree(TypeTree(param.actualType), SetParameterTypeTree),
                newTermName("applied")
              ),
              List(param.tree)
            )
          }
        }
      }
      queryString.append(Literal(Constant(rawQueryParts.last)))
      val pconv =
        if(remaining.isEmpty) Select(SetParameterTree, newTermName("SetUnit"))
        else Apply(
          Select(SetParameterTree, newTermName("apply")),
          List(
            Function(
              List(
                ValDef(Modifiers(Flag.PARAM), newTermName("u"), TypeTree(), EmptyTree),
                ValDef(Modifiers(Flag.PARAM), newTermName("pp"), TypeTree(), EmptyTree)
              ),
              Block(
                remaining.toList map ( sp =>
                  Apply(
                    Select(sp.tree, newTermName("apply")),
                    List(Ident(newTermName("u")), Ident(newTermName("pp")))
                  )
                ), Literal(Constant(()))
              )
            )
          )
        )
      (fuse(queryString.result()), pconv)
    }
  }

  lazy val queryParts: Tree =
    Apply(Select(VectorTree, newTermName("apply")), interpolationResultParams._1)

  def staticQueryString: String = interpolationResultParams._1 match {
    case Literal(Constant(s: String)) :: Nil => s
    case _ => c.abort(c.enclosingPosition, "Only constant strings may be used after '#$' in 'tsql' interpolation")
  }

  lazy val pconvTree: Tree = interpolationResultParams._2
}
