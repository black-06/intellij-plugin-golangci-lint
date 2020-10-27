package com.ypwang.plugin.quickfix

import com.goide.psi.GoCallExpr
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoExitAfterDeferFix(element: GoCallExpr) : LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName(): String = text

    override fun getText(): String = "Replace with '${(myStartElement.element as GoCallExpr).expression.text.replaceFirst(".Fatal", ".Panic")}'"

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoCallExpr

        val expressionBuilder = StringBuilder()
        expressionBuilder.append(element.expression.text.replaceFirst(".Fatal", ".Panic"))
        expressionBuilder.append("(")
        expressionBuilder.append(element.argumentList.expressionList.joinToString(", ") { it.text })
        expressionBuilder.append(")")

        element.replace(GoElementFactory.createCallExpression(project, expressionBuilder.toString()))
    }
}