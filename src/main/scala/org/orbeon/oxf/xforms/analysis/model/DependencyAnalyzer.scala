/**
 * Copyright (C) 2015 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.analysis.model

import org.orbeon.oxf.common.ValidationException
import org.orbeon.oxf.util.ScalaUtils._
import org.orbeon.saxon.expr.{Expression, LocalVariableReference, VariableReference}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.JavaConverters._

// Analyze a tree of binds to determine expressions dependencies based on references to MIP variables, that is to binds
// which have a `name` attribute. The result is an evaluation order which satisfies the dependencies.
//
// See also: https://github.com/orbeon/orbeon-forms/issues/2186
object DependencyAnalyzer {
    
    val LoggerName = "org.orbeon.xforms.analysis.calculate"
    val Logger     = LoggerFactory.getLogger(LoggerName)
    
    private case class BindDetails(staticBind: StaticBind, name: Option[String], refs: Set[String])
    
    private object BindDetails {
        
        def fromStaticBindMIP(
            validBindNames : scala.collection.Set[String], 
            staticBind     : StaticBind, 
            mipOpt         : Option[StaticBind#XPathMIP]
        ): Option[BindDetails] =
            mipOpt map { mip ⇒
                    
                val compiledExpr = mip.compiledExpression
                val expr         = compiledExpr.expression.getInternalExpression
                
                def iterateExpressionTree(e: Expression): Iterator[Expression] =
                    Iterator(e) ++ (
                        e.iterateSubExpressions.asScala.asInstanceOf[Iterator[Expression]] flatMap iterateExpressionTree
                    )
                
                val externalVariableReferenceIt = iterateExpressionTree(expr) collect {
                    case vr: VariableReference if ! vr.isInstanceOf[LocalVariableReference] ⇒ vr
                }
                
                val referencedVariableNames = (
                    externalVariableReferenceIt
                    map    (_.getBinding.getVariableQName.getLocalName)
                    filter validBindNames
                )
                
                BindDetails(staticBind, staticBind.nameOpt, referencedVariableNames.to[Set])
            }
    }
    
    def determineEvaluationOrder(tree: BindTree, mip: Model.StringMIP): List[StaticBind] = {
        
        if (Logger.isDebugEnabled)
            Logger.debug(s"analyzing ${mip.name} dependencies for model ${tree.model.staticId}")
        
        val allBindsByName = tree.bindsByName
        
        val bindsWithMIPDetails = {
            
            def iterateBinds(binds: Seq[StaticBind]): Iterator[StaticBind] =
                binds.iterator flatMap (b ⇒ Iterator(b) ++ iterateBinds(b.children))
            
            val validBindNames = allBindsByName.keySet
            
            val bindsIt   = iterateBinds(tree.topLevelBinds)
            val detailsIt = bindsIt flatMap (b ⇒ BindDetails.fromStaticBindMIP(validBindNames, b, b.firstXPathMIP(mip)))
            
            detailsIt.to[List]
        }
        
        // The algorithm requires all vertices so create all the ones which are referenced by name by expressions, but
        // are not present in bindsWithMIPDetails.
        val otherBindDetailsIt = {
            
            val existingBindNames = bindsWithMIPDetails flatMap (_.name) toSet
            val referredBindNames = bindsWithMIPDetails flatMap (_.refs) toSet
            val namesToAdd        = referredBindNames -- existingBindNames
            
            for {
                name       ← namesToAdd
                staticBind ← allBindsByName.get(name)
            } yield
                BindDetails.apply(staticBind, staticBind.nameOpt, Set.empty)
            
        }
        
        def sortTopologically(bindsForSort: List[BindDetails]) = {
            @tailrec
            def visit(bindDetails: List[BindDetails], done: List[StaticBind]): List[StaticBind] =
                bindDetails partition (_.refs.isEmpty) match {
                    case (Nil, Nil) ⇒
                        done
                    case (Nil, head :: _) ⇒
                        throw new ValidationException(
                            "MIP dependency cycle found",
                            head.staticBind.locationData
                        )
                    case (noRefs, withRefs) ⇒
                        visit(
                            bindDetails = withRefs map (b ⇒ b.copy(refs = b.refs -- (noRefs flatMap (_.name)))),
                            done        = noRefs.map(_.staticBind).reverse ::: done
                        )
                }
            
            visit(bindsForSort, Nil).reverse
        }
        
        def logResult(result: List[StaticBind]) =
            if (result.nonEmpty && Logger.isDebugEnabled) {
                
                val idsToRefs = bindsWithMIPDetails map (b ⇒ b.staticBind.staticId → b.refs) toMap
                
                val maxStaticId = result map (_.staticId.size) max
                
                def explanation(staticBind: StaticBind) = {
                    
                    val staticId = staticBind.staticId
                    val refs     = idsToRefs(staticId)
                    
                    val dependsOnMsg = if (refs.isEmpty) "" else refs mkString (" (references: ", ", ", ")")
                    
                    s"  ${staticId.padTo(maxStaticId, ' ')}$dependsOnMsg"
                }
                
                val allExplanations = result map explanation mkString "\n" 
                
                Logger.debug(s"topological sort (${result.size}} nodes):\n$allExplanations")
            }
        
        // We are only interested in the binds containing the MIP
        val idsToKeep = bindsWithMIPDetails map (_.staticBind.staticId) toSet
        
        (sortTopologically(bindsWithMIPDetails ++ otherBindDetailsIt) filter (b ⇒ idsToKeep(b.staticId))) |!> logResult
    }
}
