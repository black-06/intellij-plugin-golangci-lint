package com.ypwang.plugin

import com.goide.inspections.GoInspectionUtil
import com.goide.psi.*
import com.goide.quickfix.*
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.*

private val nonAvailableFix = arrayOf<LocalQuickFix>() to null

private inline fun <reified T : PsiElement> chainFindAndHandle(
        file: PsiFile,
        offset: Int,
        handler: (T) -> Pair<Array<LocalQuickFix>, TextRange?>?
): Pair<Array<LocalQuickFix>, TextRange?> {
    var element = file.findElementAt(offset)
    while (element != null) {
        if (element is T)
            return handler(element) ?: nonAvailableFix

        element = element.parent
    }

    return nonAvailableFix
}

abstract class ProblemHandler {
    fun suggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> =
            try {
                doSuggestFix(file, issue)
            } catch (e: Exception) {
                nonAvailableFix
            }

    open fun description(issue: LintIssue): String = "${issue.Text} (${issue.FromLinter})"
    abstract fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?>
}

val defaultHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> = nonAvailableFix
}

private val namedElementHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, issue.Pos.Offset) { element: GoNamedElement ->
                when (element) {
                    is GoFieldDefinition -> {
                        val decl = element.parent
                        if (decl is GoFieldDeclaration) {
                            var start: PsiElement = decl
                            while (start.prevSibling != null && (start.prevSibling !is PsiWhiteSpaceImpl || start.prevSibling.text != "\n"))
                                start = start.prevSibling

                            var end: PsiElement = decl.nextSibling
                            while (end !is PsiWhiteSpaceImpl || end.text != "\n")
                                end = end.nextSibling

                            // remove entire line
                            arrayOf<LocalQuickFix>(GoDeleteRangeQuickFix(start, end, "Delete field '${element.identifier.text}'"))
                        } else arrayOf()
                    }
                    is GoFunctionDeclaration ->
                        arrayOf(GoDeleteQuickFix("Delete function ${element.identifier}", GoFunctionDeclaration::class.java), GoRenameToBlankQuickFix(element))
                    is GoTypeSpec ->
                        arrayOf<LocalQuickFix>(GoDeleteTypeQuickFix(element.identifier.text))
                    is GoVarDefinition ->
                        (if (GoInspectionUtil.canDeleteDefinition(element)) arrayOf(GoRenameToBlankQuickFix(element), GoDeleteVarDefinitionQuickFix(element.name))
                        else arrayOf<LocalQuickFix>(GoRenameToBlankQuickFix(element)))
                    is GoConstDefinition ->
                        (if (GoInspectionUtil.canDeleteDefinition(element)) arrayOf<LocalQuickFix>(GoDeleteConstDefinitionQuickFix(element.name)) else arrayOf())
//                    is GoMethodDeclaration -> arrayOf(GoDeleteQuickFix("Delete function", GoMethodDeclaration::class.java), GoRenameToBlankQuickFix(element))
//                    is GoLightMethodDeclaration -> arrayOf(GoDeleteQuickFix("Delete function", GoLightMethodDeclaration::class.java), GoRenameToBlankQuickFix(element))
                    else -> nonAvailableFix.first
                } to element.identifier?.textRange
            }
}

private val ineffassignHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> {
        if (issue.Text.startsWith("ineffectual assignment to")) {
            // get the variable
            val variable = issue.Text.substring(issue.Text.lastIndexOf(' ') + 1).trim('`')
            // normally cur pos is LeafPsiElement, parent should be GoVarDefinition (a := 1) or GoReferenceExpressImpl (a = 1)
            // we cannot delete/rename GoVarDefinition, as that would have surprising impact on usage below
            // while for Reference we could safely rename it to '_' without causing damage
            val element = file.findElementAt(issue.Pos.Offset)?.parent
            if (element is GoReferenceExpression && element.text == variable) {
                return arrayOf<LocalQuickFix>(GoReferenceRenameToBlankQuickFix(element)) to element.identifier.textRange
            }
        }
        return nonAvailableFix
    }
}

private val scopelintHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> =
            arrayOf<LocalQuickFix>(GoScopeLintFakeFix()) to null
}

private val interfacerHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, issue.Pos.Offset) { element: GoParameterDeclaration ->
                // last child is type signature
                arrayOf<LocalQuickFix>(GoReplaceParameterTypeFix(
                        issue.Text.substring(issue.Text.lastIndexOf(' ') + 1).trim('`'),
                        element
                )) to element.lastChild.textRange
            }
}

private val gocriticHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> =
            when {
                issue.Text.startsWith("assignOp: replace") -> {
                    var begin = issue.Text.indexOf('`')
                    var end = issue.Text.indexOf('`', begin + 1)
                    val currentAssignment = issue.Text.substring(begin + 1, end)

                    begin = issue.Text.indexOf('`', end + 1)
                    end = issue.Text.indexOf('`', begin + 1)
                    val replace = issue.Text.substring(begin + 1, end)

                    chainFindAndHandle(file, issue.Pos.Offset) { element: GoAssignmentStatement ->
                        if (element.text == currentAssignment) {
                            if (replace.endsWith("++") || replace.endsWith("--"))
                                arrayOf<LocalQuickFix>(GoReplaceElementFix(replace, element, GoIncDecStatement::class.java)) to element.textRange
                            else
                                arrayOf<LocalQuickFix>(GoReplaceElementFix(replace, element, GoAssignmentStatement::class.java)) to element.textRange
                        } else null
                    }
                }
                issue.Text.startsWith("sloppyLen:") ->
                    chainFindAndHandle(file, issue.Pos.Offset) { element: GoConditionalExpr ->
                        if (issue.Text.contains(element.text)) {
                            val searchPattern = "can be "
                            val replace = issue.Text.substring(issue.Text.indexOf(searchPattern) + searchPattern.length)
                            arrayOf<LocalQuickFix>(GoReplaceElementFix(replace, element, GoConditionalExpr::class.java)) to element.textRange
                        } else null
                    }
                issue.Text.startsWith("unslice:") ->
                    chainFindAndHandle(file, issue.Pos.Offset) { element: GoIndexOrSliceExpr ->
                        if (issue.Text.contains(element.text) && element.expression != null)
                            arrayOf<LocalQuickFix>(GoReplaceExpressionFix(element.expression!!.text, element)) to element.textRange
                        else null
                    }
                issue.Text.startsWith("captLocal:") ->
                    chainFindAndHandle(file, issue.Pos.Offset) { element: GoParamDefinition ->
                        val text = element.identifier.text
                        if (text[0].isUpperCase())
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, text[0].toLowerCase() + text.substring(1))) to element.identifier.textRange
                        else null
                    }
                else -> nonAvailableFix
            }
}

private val golintHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> =
            when {
                issue.Text.startsWith("var ") || issue.Text.startsWith("const ") -> {
                    var begin = issue.Text.indexOf('`')
                    var end = issue.Text.indexOf('`', begin + 1)
                    val curName = issue.Text.substring(begin + 1, end)

                    begin = issue.Text.indexOf('`', end + 1)
                    end = issue.Text.indexOf('`', begin + 1)
                    val newName = issue.Text.substring(begin + 1, end)

                    chainFindAndHandle(file, issue.Pos.Offset) { element: GoNamedElement ->
                        if (element.text == curName)
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, newName)) to element.identifier?.textRange
                        else null
                    }
                }
                issue.Text.startsWith("receiver name ") -> {
                    val searchPattern = "receiver name "
                    var begin = issue.Text.indexOf(searchPattern) + searchPattern.length
                    val curName = issue.Text.substring(begin, issue.Text.indexOf(' ', begin))

                    begin = issue.Text.indexOf(searchPattern, begin + 1) + searchPattern.length
                    val newName = issue.Text.substring(begin, issue.Text.indexOf(' ', begin))
                    chainFindAndHandle(file, issue.Pos.Offset) { element: GoMethodDeclaration ->
                        val receiver = element.receiver
                        if (receiver != null && receiver.identifier!!.text == curName) {
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(receiver, newName)) to receiver.identifier?.textRange
                        } else null
                    }
                }
                issue.Text.startsWith("type name will be used as ") -> {
                    val newName = issue.Text.substring(issue.Text.lastIndexOf(' ') + 1)
                    chainFindAndHandle(file, issue.Pos.Offset) { element: GoTypeSpec ->
                        if (element.identifier.text.startsWith(element.containingFile.packageName ?: "", true))
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, newName)) to element.identifier.textRange
                        else null
                    }
                }
                issue.Text == "don't use ALL_CAPS in Go names; use CamelCase" ->
                    chainFindAndHandle(file, issue.Pos.Offset) { element: GoConstDefinition ->
                        // ALL_CAPS to CamelCase
                        val replace = element.identifier.text
                                .split('_')
                                .flatMap { it.withIndex().map { iv -> if (iv.index == 0) iv.value else iv.value.toLowerCase() } }
                                .joinToString("")

                        arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, replace)) to element.identifier.textRange
                    }
                else -> nonAvailableFix
            }
}

private val whitespaceHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> {
        assert(issue.LineRange != null)
        // whitespace linter tells us the start line and end line
        var start = Int.MAX_VALUE
        var end = Int.MIN_VALUE

        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
        if (document != null) {
            val elements = mutableListOf<PsiElement>()
            for (line in issue.LineRange!!.To downTo issue.LineRange.From) {
                // line in document starts from 0
                val s = document.getLineStartOffset(line - 1)
                val e = document.getLineEndOffset(line - 1)
                start = minOf(start, s)
                end = maxOf(end, e)
                if (s == e) {
                    // whitespace line
                    val element = file.findElementAt(s)
                    if (element is PsiWhiteSpaceImpl && element.chars.all { it == '\n' })
                        elements.add(element)
                }
            }

            if (elements.isNotEmpty()) return arrayOf<LocalQuickFix>(GoDeleteElementsFix(elements)) to TextRange(start, end)
        }

        return nonAvailableFix
    }
}

// experimental
private val goconstHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, issue.Pos.Offset) { element: GoStringLiteral ->
                arrayOf<LocalQuickFix>(GoIntroduceConstStringLiteralFix(file as GoFile, element.text)) to element.textRange
            }
}

private val malignedHandler = object : ProblemHandler() {
    override fun description(issue: LintIssue): String {
        val lineBreak = issue.Text.indexOf(":\n")
        if (lineBreak != -1)
            return issue.Text.substring(0, lineBreak) + " (maligned)"

        return super.description(issue)
    }

    override fun doSuggestFix(file: PsiFile, issue: LintIssue): Pair<Array<LocalQuickFix>, TextRange?> {
        val lineBreak = issue.Text.indexOf(":\n")
        if (lineBreak != -1) {
            return chainFindAndHandle(file, issue.Pos.Offset) { element: GoTypeDeclaration ->
                arrayOf<LocalQuickFix>(
                        GoReorderStructFieldFix(
                                element.typeSpecList.first().identifier.text,
                                issue.Text.substring(lineBreak + 2).trim('`'),
                                element
                        )
                ) to element.typeSpecList.first().identifier.textRange
            }
        }

        return nonAvailableFix
    }
}

// attempt to suggest auto-fix, if possible, clarify affected PsiElement for better inspection
val quickFixHandler = mapOf(
        "structcheck" to namedElementHandler,
        "varcheck" to namedElementHandler,
        "deadcode" to namedElementHandler,
        "unused" to namedElementHandler,
        "ineffassign" to ineffassignHandler,
        "scopelint" to scopelintHandler,
        "gocritic" to gocriticHandler,
        "interfacer" to interfacerHandler,
        "whitespace" to whitespaceHandler,
        "golint" to golintHandler,
        "goconst" to goconstHandler,
        "maligned" to malignedHandler
)