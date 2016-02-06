/**
 * Copyright (C) 2010 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.action.actions

import org.orbeon.oxf.xforms._
import org.orbeon.oxf.xforms.action._
import org.orbeon.oxf.xforms.analysis._
import org.orbeon.oxf.xml.Dom4j

/**
 * 10.1.1 The action Element
 */
class XFormsActionAction extends XFormsAction {

  override def execute(actionContext: DynamicActionContext): Unit = {

    val actionInterpreter = actionContext.interpreter
    val actionElement     = actionContext.element
    val bindingContext    = actionContext.bindingContext

    actionContext.partAnalysis.scriptsByPrefixedId.get(actionInterpreter.getActionPrefixedId(actionElement)) match {
      case Some(script @ StaticScript(_, JavaScriptScriptType, paramExpressions, _)) ⇒
        // Evaluate script parameters if any and schedule the script to run
        actionInterpreter.containingDocument.addScriptToRun(
          ScriptInvocation(
            script,
            actionContext.interpreter.event.targetObject.getEffectiveId,
            actionContext.interpreter.eventObserver.getEffectiveId,
            // https://github.com/orbeon/orbeon-forms/issues/2499
            paramExpressions map { expr ⇒
              actionInterpreter.evaluateAsString(
                actionElement,
                bindingContext.nodeset,
                bindingContext.position,
                expr
              )
            }
          )
        )
      case Some(StaticScript(_, XPathScriptType, params, ShareableScript(_, _, body, _))) ⇒
        // Evaluate XPath expression for its side effects only
        actionInterpreter.evaluateKeepItems(
          actionElement,
          bindingContext.nodeset,
          bindingContext.position,
          body
        )
      case None ⇒
        // Grouping XForms action which executes its children actions

        val contextStack = actionInterpreter.actionXPathContext
        val partAnalysis = actionContext.partAnalysis

        // Iterate over child actions
        var variablesCount = 0
        for (childActionElement ← Dom4j.elements(actionElement)) {

          val childPrefixedId = actionInterpreter.getActionPrefixedId(childActionElement)

          Option(partAnalysis.getControlAnalysis(childPrefixedId)) match {
            case Some(variable: VariableAnalysisTrait) ⇒
              // Scope variable
              contextStack.scopeVariable(variable, actionInterpreter.getSourceEffectiveId(actionElement), false)
              variablesCount += 1
            case Some(action) ⇒
              // Run child action
              // NOTE: We execute children actions even if they happen to have `observer` or `target` attributes.
              actionInterpreter.runAction(action)
            case None ⇒
              throw new IllegalStateException
          }
        }

        // Unscope all variables
        for (_ ← 1 to variablesCount)
          contextStack.popBinding()
    }
  }
}
