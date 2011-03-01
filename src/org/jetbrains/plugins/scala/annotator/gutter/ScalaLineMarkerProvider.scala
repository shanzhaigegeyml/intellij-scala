package org.jetbrains.plugins.scala
package annotator
package gutter


import _root_.scala.collection.mutable.HashSet
import _root_.scala.collection.mutable.ArrayBuffer
import com.intellij.codeHighlighting.Pass
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi._
import com.intellij.psi.search.searches.ClassInheritorsSearch
import java.util.{Collection, List}
import lang.lexer.ScalaTokenTypes
import lang.psi.api.statements._
import lang.psi.api.toplevel.templates.ScTemplateBody
import lang.psi.api.toplevel.{ScNamedElement}
import lang.psi.impl.search.ScalaOverridengMemberSearch
import lang.psi.types.FullSignature
import com.intellij.util.NullableFunction
import lang.psi.{ScalaPsiUtil}
import com.intellij.openapi.editor.colors.{EditorColorsScheme, EditorColorsManager, CodeInsightColors}
import com.intellij.openapi.editor.markup.{SeparatorPlacement, GutterIconRenderer}
import com.intellij.codeInsight.daemon.{DaemonCodeAnalyzerSettings, LineMarkerInfo, LineMarkerProvider}
import lang.psi.api.toplevel.typedef.{ScObject, ScMember, ScTypeDefinition, ScTrait}
import javax.swing.Icon
import GutterIcons._


/**
 * User: Alexander Podkhalyuzin
 * Date: 31.10.2008
 */

class ScalaLineMarkerProvider(daemonSettings: DaemonCodeAnalyzerSettings, colorsManager: EditorColorsManager)
        extends LineMarkerProvider with ScalaSeparatorProvider {

  def getLineMarkerInfo(element: PsiElement): LineMarkerInfo[_ <: PsiElement] = {
    val gator = getGatorInfo(element)
    if(daemonSettings.SHOW_METHOD_SEPARATORS && isSeparatorNeeded(element)) {
      if(gator == null) {
        return addSeparatorInfo(createMarkerInfo(element))
      } else {
        return addSeparatorInfo(gator)
      }
    } else {
      return gator
    }
  }

  def createMarkerInfo(element: PsiElement) = {
    new LineMarkerInfo[PsiElement](
            element, element.getTextRange, null, Pass.UPDATE_ALL,
            NullableFunction.NULL.asInstanceOf[com.intellij.util.Function[PsiElement,String]],
            null,GutterIconRenderer.Alignment.RIGHT)
  }

  def addSeparatorInfo(info: LineMarkerInfo[_ <: PsiElement]) = {
    info.separatorColor = colorsManager.getGlobalScheme.getColor(CodeInsightColors.METHOD_SEPARATORS_COLOR)
    info.separatorPlacement = SeparatorPlacement.TOP
    info
  }

  def getGatorInfo(element: PsiElement): LineMarkerInfo[_ <: PsiElement] = {
    if (element.getNode.getElementType == ScalaTokenTypes.tIDENTIFIER) {
      val offset = element.getTextRange.getStartOffset
      def getParent: PsiElement = {
        var e = element
        while (e != null && !e.isInstanceOf[ScFunction] && !e.isInstanceOf[ScValue] && !e.isInstanceOf[ScVariable] && !e.isInstanceOf[ScObject]) e = e.getParent
        e
      }
      def marker(element: PsiElement, icon: Icon, typez: ScalaMarkerType): LineMarkerInfo[PsiElement] =
        new LineMarkerInfo[PsiElement](element, offset, icon, Pass.UPDATE_ALL, typez.fun, typez.handler, GutterIconRenderer.Alignment.LEFT)

      getParent match {
        case method: ScFunction if method.getParent.isInstanceOf[ScTemplateBody] && method.nameId == element => {
          val signatures = (HashSet[FullSignature](method.superSignatures: _*)).toSeq
          val icon = if (GutterUtil.isOverrides(method)) OVERRIDING_METHOD_ICON else IMPLEMENTING_METHOD_ICON
          val typez = ScalaMarkerType.OVERRIDING_MEMBER
          if (signatures.length > 0) {
            return marker(method.nameId, icon, typez)
          }
        }
        case x@(_: ScValue | _: ScVariable) if x.getParent.isInstanceOf[ScTemplateBody] &&
                x.asInstanceOf[ScDeclaredElementsHolder].
                        declaredElements.exists(_.asInstanceOf[ScNamedElement].nameId == element) => {
          val signature = new ArrayBuffer[FullSignature]
          val bindings = x match {case v: ScDeclaredElementsHolder => v.declaredElements case _ => return null}
          for (z <- bindings) signature ++= ScalaPsiUtil.superValsSignatures(z)
          val icon = if (GutterUtil.isOverrides(x)) OVERRIDING_METHOD_ICON else IMPLEMENTING_METHOD_ICON
          val typez = ScalaMarkerType.OVERRIDING_MEMBER
          if (signature.length > 0) {
            val token = x match {
              case v: ScValue => v.getValToken
              case v: ScVariable => v.getVarToken
            }
            return marker(token, icon, typez)
          }
        }
        case x : ScObject if x.getParent.isInstanceOf[ScTemplateBody] && x.nameId == element => {
          val signature = ScalaPsiUtil.superValsSignatures(x)
          val icon = if (GutterUtil.isOverrides(x)) OVERRIDING_METHOD_ICON else IMPLEMENTING_METHOD_ICON
          val typez = ScalaMarkerType.OVERRIDING_MEMBER
          if (signature.length > 0) {
            return marker(x.getObjectToken, icon, typez)
          }
        }
        case _ =>
      }
    }
    return null
  }

  def collectSlowLineMarkers(elements: List[PsiElement], result: Collection[LineMarkerInfo[_ <: PsiElement]]) {
    ApplicationManager.getApplication().assertReadAccessAllowed()

    val members = new ArrayBuffer[PsiMember]
    for (element <- elements.toArray) {
      ProgressManager.checkCanceled

      element match {
        case clazz: ScTypeDefinition => {
          GutterUtil.collectInheritingClasses(clazz, result)
        }
        case x: PsiMember => members += x
        case _ =>
      }
    }
    if (!members.isEmpty) {
      GutterUtil.collectOverridingMembers(members.toArray, result)
    }
  }
}

private object GutterUtil {
  def collectInheritingClasses(clazz: ScTypeDefinition, result: Collection[LineMarkerInfo[_ <: PsiElement]]) {
    if ("scala.ScalaObject".equals(clazz.getQualifiedName)) return

    val inheritor = ClassInheritorsSearch.search(clazz, false).findFirst
    if (inheritor != null) {
      val offset = clazz.getTextOffset
      val icon = clazz match {
        case _: ScTrait => IMPLEMENTED_INTERFACE_MARKER_RENDERER
        case _ => SUBCLASSED_CLASS_MARKER_RENDERER
      }
      val typez = ScalaMarkerType.SUBCLASSED_CLASS
      val info = new LineMarkerInfo[PsiElement](clazz.nameId, offset, icon, Pass.UPDATE_OVERRIDEN_MARKERS, typez.fun, typez.handler)
      result.add(info)
    }
  }

  def collectOverridingMembers(members: Array[PsiMember], result: Collection[LineMarkerInfo[_ <: PsiElement]]) {
    for (member <- members if !member.isInstanceOf[PsiMethod] || !member.asInstanceOf[PsiMethod].isConstructor) {
      ProgressManager.checkCanceled
      val offset = member.getTextOffset
      val members = member match {
        case memb: ScFunction => Array[PsiNamedElement](memb)
        case d: ScDeclaredElementsHolder => d.declaredElements.toArray
        case _ => Array[PsiNamedElement]()
      }
      val overrides = new ArrayBuffer[PsiNamedElement]
      for (member <- members if overrides.length == 0) overrides ++= ScalaOverridengMemberSearch.search(member, false)
      if (overrides.length > 0) {
        val icon = if (!GutterUtil.isAbstract(member)) OVERRIDEN_METHOD_MARKER_RENDERER else IMPLEMENTED_INTERFACE_MARKER_RENDERER
        val typez = ScalaMarkerType.OVERRIDDEN_MEMBER
        val info = new LineMarkerInfo[PsiElement](member match {case memb: ScNamedElement => memb.nameId
          case _ => member}, offset, icon, Pass.UPDATE_OVERRIDEN_MARKERS, typez.fun, typez.handler)
        result.add(info)
      }
    }
  }

  def isOverrides(element: PsiElement) = {
    element match {
      case method: PsiMethod => method.isInstanceOf[ScFunctionDeclaration] ||
                  method.hasModifierProperty("override")
      case value: ScValue => value.isInstanceOf[ScValueDeclaration] || value.hasModifierProperty("override")
      case value: ScVariable => value.isInstanceOf[ScVariableDeclaration] || value.hasModifierProperty("override")
      case _ => false
    }
  }

  def isAbstract(element: PsiElement) = element match {
    case method: ScFunctionDeclaration => true
    case value: ScValueDeclaration => true
    case variable: ScVariableDeclaration => true
    case _ => false
  }
}
